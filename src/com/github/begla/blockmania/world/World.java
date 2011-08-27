/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.begla.blockmania.world;

import com.github.begla.blockmania.Configuration;
import com.github.begla.blockmania.RenderableObject;
import com.github.begla.blockmania.ShaderManager;
import com.github.begla.blockmania.blocks.Block;
import com.github.begla.blockmania.generators.*;
import com.github.begla.blockmania.player.Player;
import com.github.begla.blockmania.utilities.FastRandom;
import com.github.begla.blockmania.utilities.Helper;
import com.github.begla.blockmania.utilities.MathHelper;
import com.github.begla.blockmania.utilities.VectorPool;
import javolution.util.FastList;
import javolution.util.FastSet;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.newdawn.slick.opengl.Texture;
import org.newdawn.slick.opengl.TextureLoader;
import org.newdawn.slick.util.ResourceLoader;
import org.xml.sax.InputSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL11.*;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class World extends RenderableObject {

    private final Vector2f _cloudOffset = new Vector2f();
    private final Vector2f _windDirection = new Vector2f(0.25f, 0);
    private double _lastWindUpdate = 0;
    private short _nextWindUpdateInSeconds = 32;
    /* ------ */
    private long _lastDaytimeMeasurement = Helper.getInstance().getTime();
    private long _latestDirtEvolvement = Helper.getInstance().getTime();
    /* ------ */
    private static Texture _textureSun, _textureMoon;
    /* ------ */
    private float _time = 0f;
    private float _daylight = 1.0f;
    private Player _player;
    private Vector3f _spawningPoint;
    /* ------ */
    private boolean _updatingEnabled = false;
    private boolean _updateThreadAlive = true;
    private final Thread _updateThread;
    /* ------ */
    private final ChunkUpdateManager _chunkUpdateManager = new ChunkUpdateManager(this);
    private final ChunkCache _chunkCache = new ChunkCache(this);
    /* ------ */
    private final ChunkGeneratorTerrain _generatorTerrain;
    private final ChunkGeneratorForest _generatorForest;
    private final ChunkGeneratorResources _generatorResources;
    private final ChunkGeneratorFlora _generatorGrass;
    private final ObjectGeneratorTree _generatorTree;
    private final ObjectGeneratorPineTree _generatorPineTree;
    private final ObjectGeneratorFirTree _generatorFirTree;
    private final FastRandom _rand;
    /* ------ */
    private String _title, _seed;
    /* ----- */
    private FastSet<Chunk> _visibleChunks;
    /* ----- */
    private static int _dlSunMoon = -1;
    private static int _dlClouds = -1;
    private static boolean[][] _clouds;

    /**
     * Initializes a new world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     * @param p     The player
     */
    public World(String title, String seed, Player p) {
        if (title == null) {
            throw new IllegalArgumentException("No title provided.");
        }

        if (title.isEmpty()) {
            throw new IllegalArgumentException("No title provided.");
        }

        if (seed == null) {
            throw new IllegalArgumentException("No seed provided.");
        }

        if (seed.isEmpty()) {
            throw new IllegalArgumentException("No seed provided.");
        }

        if (p == null) {
            throw new IllegalArgumentException("No player provided.");
        }

        this._player = p;
        this._title = title;
        this._seed = seed;

        // If loading failed accept the given seed
        if (!loadMetaData()) {
            // Generate the save directory if needed
            File dir = new File(getWorldSavePath());
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        // Init. generators
        _generatorTerrain = new ChunkGeneratorTerrain(seed);
        _generatorForest = new ChunkGeneratorForest(seed);
        _generatorResources = new ChunkGeneratorResources(seed);
        _generatorTree = new ObjectGeneratorTree(this, seed);
        _generatorPineTree = new ObjectGeneratorPineTree(this, seed);
        _generatorFirTree = new ObjectGeneratorFirTree(this, seed);
        _generatorGrass = new ChunkGeneratorFlora(seed);

        // Init. random generator
        _rand = new FastRandom(seed.hashCode());
        resetPlayer();

        _visibleChunks = fetchVisibleChunks();

        _updateThread = new Thread(new Runnable() {

            public void run() {
                while (true) {
                    /*
                     * Checks if the thread should be killed.
                     */
                    if (!_updateThreadAlive) {
                        return;
                    }

                    /*
                     * Puts the thread to sleep 
                     * if updating is disabled.
                     */
                    if (!_updatingEnabled) {
                        synchronized (_updateThread) {
                            try {
                                _updateThread.wait();
                            } catch (InterruptedException ex) {
                                Helper.LOGGER.log(Level.SEVERE, ex.toString());
                            }
                        }
                    }

                    /*
                     * Update chunks queued for updating.
                     */
                    _chunkUpdateManager.updateChunk();

                    // Update the the list of visible chunks
                    synchronized (_updateThread) {
                        _visibleChunks = fetchVisibleChunks();
                    }

                    /*
                     * Update the time of day.
                     */
                    updateDaytime();

                    /*
                     * Evolve chunks.
                     */
                    replantDirt();

                }
            }
        });
    }

    /**
     * Stops the updating thread and writes all chunks to disk.
     */
    public void dispose() {
        Helper.LOGGER.log(Level.INFO, "Disposing world {0} and saving all chunks.", _title);

        synchronized (_updateThread) {
            _updateThreadAlive = false;
            _updateThread.notify();
        }

        saveMetaData();
        _chunkCache.writeAllChunksToDisk();
    }

    /**
     * Updates the time of the world. A day in Blockmania takes 12 minutes and the
     * time is updated every 15 seconds.
     */
    private void updateDaytime() {
        if (Helper.getInstance().getTime() - _lastDaytimeMeasurement >= 100) {
            setTime(_time + 1f / ((5f * 60f * 10f)));
            _lastDaytimeMeasurement = Helper.getInstance().getTime();
        }
    }

    /**
     *
     */
    private void updateDaylight() {
        // Sunrise
        if (_time < 0.1f && _time > 0.0f) {
            _daylight = _time / 0.1f;
        } else if (_time >= 0.1 && _time <= 0.5f) {
            _daylight = 1.0f;
        }

        // Sunset
        if (_time > 0.5f && _time < 0.6f) {
            _daylight = 1.0f - (_time - 0.5f) / 0.1f;
        } else if (_time >= 0.6f && _time <= 1.0f) {
            _daylight = 0.0f;
        }
    }

    /**
     *
     */
    private void replantDirt() {
        // Pick one chunk for grass updates every 1000 ms
        if (Helper.getInstance().getTime() - _latestDirtEvolvement > 1000) {

            // Do NOT replant chunks during the night...
            if (isNighttime()) {
                _latestDirtEvolvement = Helper.getInstance().getTime();
                return;
            }

            for (FastSet.Record n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end; ) {

                Chunk c = _visibleChunks.valueOf(n);

                if (c != null) {
                    if (!c.isFresh() && !c.isDirty() && !c.isLightDirty()) {
                        _generatorGrass.generate(c);
                        _chunkUpdateManager.queueChunkForUpdate(c, false, false, false);
                    }

                    _latestDirtEvolvement = Helper.getInstance().getTime();
                }
            }
        }
    }

    /**
     * Queues all displayed chunks for updating.
     */
    public void updateAllChunks() {
        synchronized (_updateThread) {
            for (FastSet.Record n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end; ) {
                _chunkUpdateManager.queueChunkForUpdate(_visibleChunks.valueOf(n), false, true, false);
            }
        }
    }

    /**
     * Init. the static resources.
     */
    public static void init() {
        try {
            Helper.LOGGER.log(Level.INFO, "Loading world textures...");
            _textureSun = TextureLoader.getTexture("png", ResourceLoader.getResource("DATA/sun.png").openStream(), GL_NEAREST);
            _textureSun.bind();
            _textureMoon = TextureLoader.getTexture("png", ResourceLoader.getResource("DATA/moon.png").openStream(), GL_NEAREST);
            _textureMoon.bind();
            Helper.LOGGER.log(Level.INFO, "Finished loading world textures!");
        } catch (IOException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }

        /*
         * Create cloud array.
         */
        try {
            BufferedImage cloudImage = ImageIO.read(ResourceLoader.getResource("DATA/clouds.png").openStream());
            _clouds = new boolean[cloudImage.getWidth()][cloudImage.getHeight()];

            for (int x = 0; x < cloudImage.getWidth(); x++) {
                for (int y = 0; y < cloudImage.getHeight(); y++) {
                    if (cloudImage.getRGB(x, y) > 0) {
                        _clouds[x][y] = true;
                    }
                }
            }
        } catch (IOException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }


        // Init display lists
        _dlClouds = glGenLists(1);
        _dlSunMoon = glGenLists(1);

        generateSunMoonDisplayList();
        generateCloudDisplayList();

    }

    /**
     * Renders the world.
     */
    @Override
    public void render() {
        /**
         * Sky box.
         */
        _player.applyNormalizedModelViewMatrix();

        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glBegin(GL_QUADS);
        Primitives.drawSkyBox(getDaylight());
        glEnd();
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);

        glLoadIdentity();

        _player.applyPlayerModelViewMatrix();

        /*
         * Render the player.
         */
        _player.render();

        /*
         * Transfer the daylight value to the shaders.
         */
        ShaderManager.getInstance().enableShader("chunk");
        int daylight = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "daylight");
        GL20.glUniform1f(daylight, getDaylight());
        ShaderManager.getInstance().enableShader("cloud");
        daylight = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("cloud"), "daylight");
        GL20.glUniform1f(daylight, getDaylight());
        ShaderManager.getInstance().enableShader(null);

        renderHorizon();
        renderChunks();
    }

    /**
     * Renders the horizon.
     */
    void renderHorizon() {


        ShaderManager.getInstance().enableShader("cloud");

        /*
         * Draw clouds.
         */
        if (_dlClouds > 0) {
            glPushMatrix();
            glTranslatef(_player.getPosition().x + _cloudOffset.x, 180f, _player.getPosition().z + _cloudOffset.y);
            glCallList(_dlClouds);
            glPopMatrix();
        }

        ShaderManager.getInstance().enableShader(null);

        glPushMatrix();
        // Position the sun relatively to the player
        glTranslatef(_player.getPosition().x, Configuration.CHUNK_DIMENSIONS.y * 2.0f, Configuration.getSettingNumeric("V_DIST_Z") * Configuration.CHUNK_DIMENSIONS.z + _player.getPosition().z);
        glRotatef(-35, 1, 0, 0);

        glColor4f(1f, 1f, 1f, 1.0f);

        glEnable(GL_BLEND);
        int blend_src = glGetInteger(GL_BLEND_SRC);
        int blend_dst = glGetInteger(GL_BLEND_DST);

        glBlendFunc(GL_ONE, GL_ONE);

        if (isDaytime()) {
            _textureSun.bind();
        } else {
            _textureMoon.bind();
        }

        if (_dlSunMoon > 0) {
            glCallList(_dlSunMoon);
        }

        glDisable(GL_BLEND);
        glPopMatrix();

        glBlendFunc(blend_src, blend_dst);
    }

    /**
     * @return
     */
    FastSet<Chunk> fetchVisibleChunks() {
        FastSet<Chunk> visibleChunks = new FastSet<Chunk>();
        for (int x = -(Configuration.getSettingNumeric("V_DIST_X").intValue() / 2); x < (Configuration.getSettingNumeric("V_DIST_X").intValue() / 2); x++) {
            for (int z = -(Configuration.getSettingNumeric("V_DIST_Z").intValue() / 2); z < (Configuration.getSettingNumeric("V_DIST_Z").intValue() / 2); z++) {
                Chunk c = _chunkCache.loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);
                if (c != null) {
                    // If this chunk was not visible, update it
                    if (!isChunkVisible(c)) {
                        _chunkUpdateManager.queueChunkForUpdate(c, false, false, true);
                    }
                    visibleChunks.add(c);
                }
            }
        }

        return visibleChunks;
    }

    /**
     * Renders all active chunks.
     */
    void renderChunks() {
        synchronized (_updateThread) {
            for (FastSet.Record n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end; ) {
                _visibleChunks.valueOf(n).render(false);
            }
            for (FastSet.Record n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end; ) {
                _visibleChunks.valueOf(n).render(true);
            }
        }
    }

    /**
     * Update all dirty display lists.
     */
    @Override
    public void update() {
        _chunkUpdateManager.updateDisplayLists();

        // Move the clouds a bit each update
        _cloudOffset.x += _windDirection.x;
        _cloudOffset.y += _windDirection.y;

        if (_cloudOffset.x >= _clouds.length * 16 / 2 || _cloudOffset.x <= -(_clouds.length * 16 / 2)) {
            _windDirection.x = -_windDirection.x;
        } else if (_cloudOffset.y >= _clouds.length * 32 / 2 || _cloudOffset.y <= -(_clouds.length * 32 / 2)) {
            _windDirection.y = -_windDirection.y;
        }

        if (Helper.getInstance().getTime() - _lastWindUpdate > _nextWindUpdateInSeconds * 1000) {
            _windDirection.x = (float) _rand.randomDouble() / 4f;
            _windDirection.y = (float) _rand.randomDouble() / 4f;
            _nextWindUpdateInSeconds = (short) (Math.abs(_rand.randomInt()) % 16 + 32);
            _lastWindUpdate = Helper.getInstance().getTime();
        }

    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param x The X-coordinate of the block
     * @return The X-coordinate of the chunk
     */
    private int calcChunkPosX(int x) {
        return (x / (int) Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param z The Z-coordinate of the block
     * @return The Z-coordinate of the chunk
     */
    private int calcChunkPosZ(int z) {
        return (z / (int) Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The X-coordinate of the block within the world
     * @param x2 The X-coordinate of the chunk within the world
     * @return The X-coordinate of the block within the chunk
     */
    private int calcBlockPosX(int x1, int x2) {
        x1 = x1 % (Configuration.getSettingNumeric("V_DIST_X").intValue() * (int) Configuration.CHUNK_DIMENSIONS.x);
        return (x1 - (x2 * (int) Configuration.CHUNK_DIMENSIONS.x));
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param z1 The Z-coordinate of the block within the world
     * @param z2 The Z-coordinate of the chunk within the world
     * @return The Z-coordinate of the block within the chunk
     */
    private int calcBlockPosZ(int z1, int z2) {
        z1 = z1 % (Configuration.getSettingNumeric("V_DIST_Z").intValue() * (int) Configuration.CHUNK_DIMENSIONS.z);
        return (z1 - (z2 * (int) Configuration.CHUNK_DIMENSIONS.z));
    }

    /**
     * Places a block of a specific type at a given position and refreshes the
     * corresponding light values.
     *
     * @param x         The X-coordinate
     * @param y         The Y-coordinate
     * @param z         The Z-coordinate
     * @param type      The type of the block to set
     * @param update    If set the affected chunk is queued for updating
     * @param overwrite
     */
    public final void setBlock(int x, int y, int z, byte type, boolean update, boolean overwrite) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c == null) {
            return;
        }

        if (overwrite || c.getBlock(blockPosX, y, blockPosZ) == 0x0) {

            byte currentValue = getLight(x, y, z, Chunk.LIGHT_TYPE.SUN);

            if (Block.getBlockForType(c.getBlock(blockPosX, y, blockPosZ)).isRemovable()) {
                c.setBlock(blockPosX, y, blockPosZ, type);
            }

            if (update) {
                /*
                 * Update sunlight.
                 */
                c.refreshSunlightAtLocalPos(blockPosX, blockPosZ, true, true);
                c.refreshLightAtLocalPos(blockPosX, y, blockPosZ, Chunk.LIGHT_TYPE.SUN);

                byte newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.SUN);

                /*
                 * Spread sunlight.
                 */
                if (newValue > currentValue) {
                    c.spreadLight(blockPosX, y, blockPosZ, newValue, Chunk.LIGHT_TYPE.SUN);
                }

                /*
                 * Spread light of block light sources.
                 */
                byte luminance = Block.getBlockForType(type).getLuminance();

                /*
                 * Is this block glowing?
                 */
                if (luminance > 0) {

                    currentValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);
                    c.setLight(blockPosX, y, blockPosZ, luminance, Chunk.LIGHT_TYPE.BLOCK);
                    newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);

                    /*
                     * Spread the light if the luminance is brighter than the
                     * current value.
                     */
                    if (newValue > currentValue) {
                        c.spreadLight(blockPosX, y, blockPosZ, luminance, Chunk.LIGHT_TYPE.BLOCK);
                    }

                }

                /*
                 * Update the block light intensity of the current block.
                 */
                c.refreshLightAtLocalPos(blockPosX, y, blockPosZ, Chunk.LIGHT_TYPE.BLOCK);

                /*
                 * Finally queue the chunk and its neighbors for updating.
                 */
                _chunkUpdateManager.queueChunkForUpdate(c, true, false, false);
            }
        }
    }

    /**
     * @param pos
     * @return
     */
    public final byte getBlockAtPosition(Vector3f pos) {
        return getBlock((int) (pos.x + 0.5f), (int) (pos.y + 0.5f), (int) (pos.z + 0.5f));
    }

    /**
     * Returns the block at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The type of the block
     */
    public final byte getBlock(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getBlock(blockPosX, y, blockPosZ);
        }

        return -1;
    }

    /**
     * Returns true if the block is surrounded by blocks within the N4-neighborhood on the xz-plane.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return
     */
    public final boolean isBlockSurrounded(int x, int y, int z) {
        return (getBlock(x + 1, y, z) > 0 || getBlock(x - 1, y, z) > 0 || getBlock(x, y, z + 1) > 0 || getBlock(x, y, z - 1) > 0);
    }

    /**
     * Returns the light value at the given position.
     *
     * @param x    The X-coordinate
     * @param y    The Y-coordinate
     * @param z    The Z-coordinate
     * @param type
     * @return The light value
     */
    public final byte getLight(int x, int y, int z, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getLight(blockPosX, y, blockPosZ, type);
        }

        return 15;
    }

    /**
     * Sets the light value at the given position.
     *
     * @param x      The X-coordinate
     * @param y      The Y-coordinate
     * @param z      The Z-coordinate
     * @param intens The light intensity value
     * @param type
     */
    public void setLight(int x, int y, int z, byte intens, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);


        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            c.setLight(blockPosX, y, blockPosZ, intens, type);
        }
    }

    /**
     * TODO
     *
     * @param x
     * @param spreadLight
     * @param refreshSunlight
     * @param z
     */
    public void refreshSunlightAt(int x, int z, boolean spreadLight, boolean refreshSunlight) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);


        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            c.refreshSunlightAtLocalPos(blockPosX, blockPosZ, spreadLight, refreshSunlight);
        }
    }

    /**
     * Recursive light calculation.
     *
     * @param x
     * @param y
     * @param z
     * @param lightValue
     * @param depth
     * @param type
     */
    public void spreadLight(int x, int y, int z, byte lightValue, int depth, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x) % Configuration.getSettingNumeric("V_DIST_X").intValue();
        int chunkPosZ = calcChunkPosZ(z) % Configuration.getSettingNumeric("V_DIST_Z").intValue();

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
        if (c != null) {
            c.spreadLight(blockPosX, y, blockPosZ, lightValue, depth, type);
        }
    }

    /**
     * Returns the daylight value.
     *
     * @return The daylight value
     */
    public float getDaylight() {
        return _daylight;
    }

    /**
     * Returns the player.
     *
     * @return The player
     */
    public Player getPlayer() {
        return _player;
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Configuration.CHUNK_DIMENSIONS.z);
    }


    /**
     * Displays some information about the world formatted as a string.
     *
     * @return String with world information
     */
    @Override
    public String toString() {
        return String.format("world (cdl: %d, cn: %d, cache: %d, ud: %fs, seed: \"%s\", title: \"%s\")", _chunkUpdateManager.updatesDLSize(), _chunkUpdateManager.updatesSize(), _chunkCache.size(), _chunkUpdateManager.getMeanUpdateDuration() / 1000d, _seed, _title);
    }

    /**
     * Starts the updating thread.
     */
    public void startUpdateThread() {
        _updatingEnabled = true;
        _updateThread.start();
    }

    /**
     * Resumes the updating thread.
     */
    public void resumeUpdateThread() {
        _updatingEnabled = true;
        synchronized (_updateThread) {
            _updateThread.notify();
        }
    }

    /**
     * Safely suspends the updating thread.
     */
    public void suspendUpdateThread() {
        _updatingEnabled = false;
    }

    /**
     * Sets the time of the world.
     *
     * @param time The time to set
     */
    public void setTime(float time) {
        _time = time;

        if (_time < 0) {
            _time = 1.0f;
        } else if (_time > 1.0f) {
            _time = 0.0f;
        }

        updateDaylight();
    }

    /**
     * @return
     */
    public ObjectGeneratorPineTree getGeneratorPineTree() {
        return _generatorPineTree;
    }

    /**
     * @return
     */
    public ObjectGeneratorTree getGeneratorTree() {
        return _generatorTree;
    }

    /**
     * @return
     */
    public ObjectGeneratorFirTree getGeneratorFirTree() {
        return _generatorFirTree;
    }

    /**
     * Returns true if it is daytime.
     *
     * @return
     */
    boolean isDaytime() {
        return _time > 0.075f && _time < 0.575;
    }

    /**
     * Returns true if it is nighttime.
     *
     * @return
     */
    boolean isNighttime() {
        return !isDaytime();
    }

    /**
     * @param x
     * @param z
     * @return
     */
    public Chunk prepareNewChunk(int x, int z) {
        FastList<ChunkGenerator> gs = new FastList<ChunkGenerator>();
        gs.add(_generatorTerrain);
        gs.add(_generatorResources);
        gs.add(_generatorForest);

        // Generate a new chunk and return it
        return new Chunk(this, VectorPool.getVector(x, 0, z), gs);
    }

    /**
     * @param c
     * @return
     */
    public boolean isChunkVisible(Chunk c) {
        return _visibleChunks != null && _visibleChunks.contains(c);

    }

    /**
     *
     */
    public void printPlayerChunkPosition() {
        int chunkPosX = calcChunkPosX((int) _player.getPosition().x);
        int chunkPosZ = calcChunkPosX((int) _player.getPosition().z);
        System.out.println(_chunkCache.getChunkByKey(MathHelper.cantorize(chunkPosX, chunkPosZ)));
    }

    /**
     * @return
     */
    public int getAmountGeneratedChunks() {
        return _chunkUpdateManager.getAmountGeneratedChunks();
    }

    /**
     * @return
     */
    private Vector3f findSpawningPoint() {
        for (int xz = 1024; ; xz++) {
            if (_generatorTerrain.calcDensity(xz, 30, xz) > 0.01f) {
                return new Vector3f(xz, 30, xz);
            }
        }
    }

    /**
     * Sets the spawning point to the player's current position.
     */
    public void setSpawningPoint() {
        _spawningPoint = new Vector3f(_player.getPosition());
    }

    /**
     *
     */
    public void resetPlayer() {
        if (_spawningPoint == null) {
            _spawningPoint = findSpawningPoint();
            _player.resetPlayer();
            _player.setPosition(_spawningPoint);
        } else {
            _player.resetPlayer();
            _player.setPosition(_spawningPoint);
        }
    }

    /**
     * @return
     */
    public String getWorldSavePath() {
        return String.format("SAVED_WORLDS/%s", _title);

    }

    /**
     * @return
     */
    private boolean saveMetaData() {
        File f = new File(String.format("%s/Metadata.xml", getWorldSavePath()));

        try {
            f.createNewFile();
        } catch (IOException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }

        Element root = new Element("World");
        Document doc = new Document(root);

        // Save the world metadata
        root.setAttribute("seed", _seed);
        root.setAttribute("title", _title);
        root.setAttribute("time", Float.toString(_time));

        // Save the player metadata
        Element player = new Element("Player");
        player.setAttribute("x", Float.toString(_player.getPosition().x));
        player.setAttribute("y", Float.toString(_player.getPosition().y));
        player.setAttribute("z", Float.toString(_player.getPosition().z));
        root.addContent(player);


        XMLOutputter outputter = new XMLOutputter();
        FileOutputStream output;

        try {
            output = new FileOutputStream(f);

            try {
                outputter.output(doc, output);
            } catch (IOException ex) {
                Helper.LOGGER.log(Level.SEVERE, null, ex);
            }

            return true;
        } catch (FileNotFoundException ex) {
            Helper.LOGGER.log(Level.SEVERE, null, ex);
        }


        return false;
    }

    /**
     * @return
     */
    private boolean loadMetaData() {
        File f = new File(String.format("%s/Metadata.xml", getWorldSavePath()));

        try {
            SAXBuilder sxbuild = new SAXBuilder();
            InputSource is = new InputSource(new FileInputStream(f));
            Document doc;
            try {
                doc = sxbuild.build(is);
                Element root = doc.getRootElement();
                Element player = root.getChild("Player");

                _seed = root.getAttribute("seed").getValue();
                _spawningPoint = VectorPool.getVector(Float.parseFloat(player.getAttribute("x").getValue()), Float.parseFloat(player.getAttribute("y").getValue()), Float.parseFloat(player.getAttribute("z").getValue()));
                _title = root.getAttributeValue("title");
                setTime(Float.parseFloat(root.getAttributeValue("time")));

                return true;

            } catch (JDOMException ex) {
                Helper.LOGGER.log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Helper.LOGGER.log(Level.SEVERE, null, ex);
            }

        } catch (FileNotFoundException ex) {
            // Metadata.xml not present
        }

        return false;
    }

    /**
     * @return
     */
    public ChunkCache getChunkCache() {
        return _chunkCache;
    }

    /**
     * Generates the cloud display list.
     */
    private static void generateCloudDisplayList() {
        try {
            glNewList(_dlClouds, GL_COMPILE);
            glBegin(GL_QUADS);

            int length = _clouds.length;

            for (int x = 0; x < length; x++) {
                for (int y = 0; y < length; y++) {
                    if (!_clouds[x][y]) {
                        Primitives.drawCloud(32, 8, 16, x * 32f - (length / 2 * 32f), 0, y * 16f - (length / 2 * 16f));
                    }
                }
            }

            glEnd();
            glEndList();

        } catch (Exception ignored) {
        }

    }

    /**
     *
     */
    private static void generateSunMoonDisplayList() {
        glNewList(_dlSunMoon, GL_COMPILE);
        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 0.0f);
        glVertex3f(Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 1.0f);
        glVertex3f(Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(0.f, 1.0f);
        glVertex3f(-Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glEnd();
        glEndList();
    }

    /**
     * @return
     */
    public boolean isUpdateThreadRunning() {
        return !_updateThread.isInterrupted() && _updateThread.isAlive();
    }
}
