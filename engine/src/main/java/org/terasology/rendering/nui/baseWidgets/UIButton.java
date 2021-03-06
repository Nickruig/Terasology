/*
 * Copyright 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.nui.baseWidgets;

import com.google.common.collect.Lists;
import org.terasology.input.MouseInput;
import org.terasology.math.Vector2i;
import org.terasology.rendering.assets.TextureRegion;
import org.terasology.rendering.assets.subtexture.Subtexture;
import org.terasology.rendering.nui.AbstractWidget;
import org.terasology.rendering.nui.BaseInteractionListener;
import org.terasology.rendering.nui.Canvas;

import java.util.List;

/**
 * @author Immortius
 */
public class UIButton extends AbstractWidget {
    public static final String HOVER_MODE = "hover";
    public static final String DOWN_MODE = "down";

    private TextureRegion image;
    private String text = "";

    private boolean down;

    private List<ButtonEventListener> listeners = Lists.newArrayList();

    private BaseInteractionListener interactionListener = new BaseInteractionListener() {

        @Override
        public boolean onMouseClick(MouseInput button, Vector2i pos) {
            if (button == MouseInput.MOUSE_LEFT) {
                down = true;
                return true;
            }
            return false;
        }

        @Override
        public void onMouseRelease(MouseInput button, Vector2i pos) {
            if (button == MouseInput.MOUSE_LEFT) {
                if (isMouseOver()) {
                    activate();
                }
                down = false;
            }
        }
    };

    public UIButton() {
    }

    public UIButton(String id) {
        super(id);
    }

    public UIButton(String id, String text) {
        super(id);
        this.text = text;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (image != null) {
            canvas.drawTexture(image);
        }
        canvas.drawText(text);
        canvas.addInteractionRegion(interactionListener);
    }

    @Override
    public String getMode() {
        if (down) {
            return DOWN_MODE;
        } else if (interactionListener.isMouseOver()) {
            return HOVER_MODE;
        }
        return DEFAULT_MODE;
    }

    private void activate() {
        for (ButtonEventListener listener : listeners) {
            listener.onButtonActivated(this);
        }
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setImage(Subtexture image) {
        this.image = image;
    }

    public TextureRegion getImage() {
        return image;
    }

    public void setImage(TextureRegion image) {
        this.image = image;
    }

    public void subscribe(ButtonEventListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(ButtonEventListener listener) {
        listeners.remove(listener);
    }
}
