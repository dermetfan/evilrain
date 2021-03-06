/*******************************************************************************
 * Copyright 2013 See AUTHORS file.
 *
 * Licensed under the GNU GENERAL PUBLIC LICENSE V3
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.gnu.org/licenses/gpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.alex.rain.stages;

import com.alex.rain.RainGame;
import com.alex.rain.helpers.LiquidHelper;
import com.alex.rain.listeners.GameContactListener;
import com.alex.rain.managers.TextureManager;
import com.alex.rain.models.Cloud;
import com.alex.rain.models.Drop;
import com.alex.rain.models.Emitter;
import com.alex.rain.models.SimpleActor;
import com.alex.rain.screens.MainMenuScreen;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL10;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Box2DDebugRenderer;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.script.LuaScriptEngine;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.SimpleBindings;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GameWorld extends Stage {
    private World physicsWorld = new World(new Vector2(0, -9.8f), true);
    private List<SimpleActor> actorList = new ArrayList<SimpleActor>();
    private List<Actor> uiActorList = new ArrayList<Actor>();
    private ArrayList<Drop> dropList = new ArrayList<Drop>();
    private LiquidHelper liquidHelper;
    private LuaFunction luaOnCreateFunc;
    private LuaFunction luaOnCheckFunc;
    private LuaFunction luaOnBeginContactFunc;
    private LuaFunction luaOnEndContactFunc;
    private boolean wonGame;
    private Table table, tableControl;
    private ShaderProgram shader;
    private Sprite dropSprite, backgroundSprite;
    private final Box2DDebugRenderer debugRenderer;
    private boolean debugRendererEnabled;
    private final SpriteBatch spriteBatchShadered;
    private final PolygonSpriteBatch polygonSpriteBatch;
    private final FrameBuffer m_fbo;
    private final TextureRegion m_fboRegion;
    private float time;
    private float timeLastDrop;
    private boolean itRain;
    private Cloud cloud;
    private Emitter emitter;
    private BitmapFont font = new BitmapFont();
    private int levelNumber = 0;
    private String winHint;
    private final boolean lightVersion;
    private int dropsMax;
    private final float dropTextureRadius;
    private final float dropTextureRadiusHalf;
    private final float dropTextureRadiusQuarter;
    private boolean physicsEnabled = true;
    private boolean liquidForcesEnabled = true;
    private boolean useShader = true;
    private GameContactListener contactListener;
    public static final float WORLD_TO_BOX = 0.1f;
    public static final float BOX_TO_WORLD = 1 / WORLD_TO_BOX;
    private Skin skin = new Skin();

    public GameWorld(String name) {
        lightVersion = RainGame.isLightVersion();
        dropsMax = lightVersion ? 1000 : 1000;
        liquidHelper = new LiquidHelper(dropList, lightVersion);

        String filename = "data/" + name + ".lua";

        if(name.replaceAll("[\\D]", "").length() > 0)
            levelNumber = Integer.parseInt(name.replaceAll("[\\D]", ""));
        String filenameMain = "data/main.lua";
        ScriptEngine engine = new LuaScriptEngine();
        CompiledScript cs;

        try {
            //Reader reader = new FileReader(filename);
            if(!Gdx.files.internal(filename).exists())
                filename = "data/test.lua";
            Reader reader = new StringReader(
                    Gdx.files.internal(filenameMain).readString() + Gdx.files.internal(filename).readString());
            cs = ((Compilable)engine).compile(reader);
            SimpleBindings sb = new SimpleBindings();
            cs.eval(sb);
            luaOnCheckFunc = (LuaFunction) sb.get("onCheck");
            luaOnCreateFunc = (LuaFunction) sb.get("onCreate");
            luaOnBeginContactFunc = (LuaFunction) sb.get("onBeginContact");
            luaOnEndContactFunc = (LuaFunction) sb.get("onEndContact");
        } catch (Exception e) {
            //LogHandler.log.error(e.getMessage(), e);
            System.out.println("error: " + filename + ". " + e);
        }

        final String VERTEX = Gdx.files.internal("data/drop_shader.vert").readString();
        final String FRAGMENT = lightVersion ?
                Gdx.files.internal("data/drop_shader_light.frag").readString() :
                Gdx.files.internal("data/drop_shader.frag").readString();

        if (Gdx.graphics.isGL20Available()) {
            shader = new ShaderProgram(VERTEX, FRAGMENT);
            if(!shader.isCompiled())
                System.out.println(shader.getLog());
        }

        dropSprite = TextureManager.getInstance().getSpriteFromDefaultAtlas("drop");
        dropTextureRadius = lightVersion ? dropSprite.getWidth() * 2f : dropSprite.getWidth();
        dropTextureRadiusHalf = dropTextureRadius / 2;
        dropTextureRadiusQuarter = dropTextureRadius / 4;
        backgroundSprite = TextureManager.getInstance().getSpriteFromDefaultAtlas("background");

        spriteBatchShadered = new SpriteBatch();
        spriteBatchShadered.setShader(shader);

        polygonSpriteBatch = new PolygonSpriteBatch();

        if(Gdx.graphics.isGL20Available()) {
            m_fbo = new FrameBuffer(Pixmap.Format.RGB565, Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
            m_fboRegion = new TextureRegion(m_fbo.getColorBufferTexture());
            m_fboRegion.flip(false, true);
        } else {
            m_fbo = null;
            m_fboRegion = null;
        }

        debugRenderer = new Box2DDebugRenderer();

        contactListener = new GameContactListener(luaOnBeginContactFunc, luaOnEndContactFunc);
        physicsWorld.setContactListener(contactListener);

        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        skin.add("white", new Texture(pixmap));

        skin.add("default", new BitmapFont());

        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.up = skin.newDrawable("white", Color.DARK_GRAY);
        textButtonStyle.down = skin.newDrawable("white", Color.DARK_GRAY);
        textButtonStyle.over = skin.newDrawable("white", Color.LIGHT_GRAY);
        textButtonStyle.font = skin.getFont("default");
        skin.add("default", textButtonStyle);

        Label.LabelStyle labelStyle = new Label.LabelStyle();
        labelStyle.font = skin.getFont("default");
        skin.add("default", labelStyle);
    }

    private void createControls() {
        tableControl = new Table();
        tableControl.setFillParent(true);
        tableControl.debug();
        addUI(tableControl);
        Sprite arrowLeftSprite = TextureManager.getInstance().getSpriteFromDefaultAtlas("arrow");
        Sprite arrowDownSprite = TextureManager.getInstance().getSpriteFromDefaultAtlas("arrow");
        Sprite arrowRightSprite = TextureManager.getInstance().getSpriteFromDefaultAtlas("arrow");
        arrowLeftSprite.setRotation(90);
        arrowDownSprite.setRotation(180);
        arrowRightSprite.setRotation(-90);
        ImageButton arrowUpButton = new ImageButton(new SpriteDrawable(TextureManager.getInstance().getSpriteFromDefaultAtlas("arrow")));
        ImageButton arrowDownButton = new ImageButton(new SpriteDrawable(arrowDownSprite));
        ImageButton arrowLeftButton = new ImageButton(new SpriteDrawable(arrowLeftSprite));
        ImageButton arrowRightButton = new ImageButton(new SpriteDrawable(arrowRightSprite));
        ImageButton actionButton = new ImageButton(new SpriteDrawable(TextureManager.getInstance().getSpriteFromDefaultAtlas("button")));
        tableControl.left();
        tableControl.bottom();
        tableControl.add(arrowUpButton).colspan(3);
        tableControl.row();
        tableControl.add(arrowLeftButton);
        tableControl.add(arrowDownButton);
        tableControl.add(arrowRightButton);
        tableControl.add(actionButton);
        arrowLeftButton.addListener(new ClickListener() {
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.LEFT, false);
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.LEFT, true);
                return true;
            }
        });
        arrowRightButton.addListener(new ClickListener() {
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.RIGHT, false);
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.RIGHT, true);
                return true;
            }
        });
        arrowUpButton.addListener(new ClickListener() {
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.UP, false);
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.UP, true);
                return true;
            }
        });
        arrowDownButton.addListener(new ClickListener() {
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.DOWN, false);
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.DOWN, true);
                return true;
            }
        });
        actionButton.addListener(new ClickListener() {
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.SPACE, false);
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                handleAction(Input.Keys.SPACE, true);
                return true;
            }
        });
    }

    public void add(SimpleActor actor) {
        actor.createPhysicsActor(physicsWorld);
        actor.prepareActor();
        actorList.add(actor);

        if(actor.getType() == SimpleActor.TYPE.CLOUD)
            cloud = (Cloud)actor;
        else if(actor.getType() == SimpleActor.TYPE.EMITTER)
            emitter = (Emitter)actor;

        if(actor.getType() == SimpleActor.TYPE.DROP) {
            getRoot().addActorAt(0, actor);
            dropList.add((Drop)actor);
        } else {
            addActor(actor);
        }

        if((cloud != null || emitter != null) && lightVersion)
            createControls();
        if(tableControl != null)
            tableControl.toFront();
    }

    public void addUI(Actor actor) {
        addActor(actor);
        uiActorList.add(actor);
    }

    public void createWorld() {
        LuaValue luaWorld = CoerceJavaToLua.coerce(this);
        if(luaOnCreateFunc != null)
            luaOnCreateFunc.call(luaWorld);
    }

    @Override
    public void act(float delta) {
        time += Gdx.graphics.getDeltaTime();
        if(liquidForcesEnabled)
            liquidHelper.applyLiquidConstraint(1/60f); // TODO: check this shit?
        if(physicsEnabled)
            physicsWorld.step(1/60f, 6, 3);

        super.act(delta);

        LuaValue luaDrop = CoerceJavaToLua.coerce(dropList);
        LuaValue retvals = luaOnCheckFunc.call(luaDrop);
        if(retvals.toboolean(1) && !wonGame) {
            wonGame = true;
            showWinnerMenu();
        }

        if(itRain && !wonGame && cloud != null && dropList.size() < dropsMax) {
            if(time - timeLastDrop > (lightVersion ? 0.09 : 0.05)) {
                Drop drop = new Drop();
                Random r = new Random();
                float offset = r.nextFloat() * cloud.getWidth() * 2/3;
                add(drop);
                drop.setPosition(new Vector2(cloud.getPosition().x - cloud.getWidth() / 3 + offset, cloud.getPosition().y));
                drop.getBody().applyForceToCenter(new Vector2(0, -drop.getBody().getMass() * 20 / delta), true);
                timeLastDrop = time;
            }
        }

        if(itRain && !wonGame && emitter != null && dropList.size() < dropsMax) {
            if(time - timeLastDrop > (lightVersion ? 0.09 : 0.05)) {
                Drop drop = new Drop();
                Random r = new Random();
                float offset = r.nextFloat() * emitter.getWidth() * 2/3;
                add(drop);
                drop.setPosition(new Vector2(emitter.getPosition().x - emitter.getWidth() / 3 + offset, emitter.getPosition().y));
                drop.getBody().applyForceToCenter(new Vector2(drop.getBody().getMass() * 30 / delta, 0), true);
                timeLastDrop = time;
            }
        }
    }

    private void showWinnerMenu() {
        if(table != null)
            return;

        table = new Table();
        table.setFillParent(true);
        table.debug();
        addUI(table);

        table.row().width(100).padTop(10);

        final Label label = new Label(wonGame ? "Victory!" : "Menu", skin);
        table.add(label);

        table.row().width(400).padTop(10);

        final TextButton button = new TextButton("Next", skin);
        table.add(button);

        table.row().width(400).padTop(10);

        final TextButton button2 = new TextButton("Restart", skin);
        table.add(button2);

        table.row().width(400).padTop(10);

        final TextButton button3 = new TextButton("Back to main menu", skin);
        table.add(button3);

        button.addListener(new ChangeListener() {
            @Override
            public void changed (ChangeEvent event, Actor actor) {
                RainGame.getInstance().setLevel("level" + (levelNumber + 1));
            }
        });

        button2.addListener(new ChangeListener() {
            @Override
            public void changed (ChangeEvent event, Actor actor) {
                RainGame.getInstance().setLevel("level" + levelNumber);
            }
        });

        button3.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                RainGame.getInstance().setMenu(new MainMenuScreen());
            }
        });
    }

    public World getPhysicsWorld() {
        return physicsWorld;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if(wonGame || cloud != null || emitter != null || dropList.size() > dropsMax)
            return true;
        Drop drop = new Drop();
        Random r = new Random();
        int offset = r.nextInt(10) - 10;
        add(drop);
        float x = screenX + offset;
        float y = Gdx.graphics.getHeight() - screenY + offset;
        drop.setPosition(new Vector2(x * 800f / Gdx.graphics.getWidth(), y * 480f / Gdx.graphics.getHeight()));

        return false;
    }

    @Override
    public boolean keyDown(int keyCode) {
        if(keyCode == Input.Keys.F4 || keyCode == Input.Keys.D)
            debugRendererEnabled = !debugRendererEnabled;
        else if(keyCode == Input.Keys.F5 || keyCode == Input.Keys.P)
            physicsEnabled = !physicsEnabled;
        else if(keyCode == Input.Keys.F6 || keyCode == Input.Keys.L)
            liquidForcesEnabled = !liquidForcesEnabled;
        else if(keyCode == Input.Keys.F7 || keyCode == Input.Keys.S)
            useShader = !useShader;
        else if(keyCode == Input.Keys.ESCAPE || keyCode == Input.Keys.Q || keyCode == Input.Keys.BACK) {
            if(table != null)
                RainGame.getInstance().setMenu(new MainMenuScreen());
            showWinnerMenu();
        } else if(keyCode == Input.Keys.LEFT || keyCode == Input.Keys.RIGHT || keyCode == Input.Keys.UP ||
                keyCode == Input.Keys.DOWN || keyCode == Input.Keys.SPACE)
            handleAction(keyCode, true);

        return false;
    }

    @Override
    public boolean keyUp(int keyCode) {
        if(keyCode == Input.Keys.LEFT || keyCode == Input.Keys.RIGHT || keyCode == Input.Keys.UP ||
                keyCode == Input.Keys.DOWN || keyCode == Input.Keys.SPACE)
            handleAction(keyCode, false);

        return true;
    }

    private void handleAction(int keyCode, boolean pressed) {
        if(keyCode == Input.Keys.LEFT) {
            if(cloud != null) {
                if(pressed) {
                    cloud.setLinearVelocity(new Vector2(-20, 0));
                    cloud.setDirection(1);
                } else {
                    cloud.setLinearVelocity(new Vector2(0, 0));
                    cloud.setDirection(0);
                }
            }
        } else if(keyCode == Input.Keys.RIGHT) {
            if(cloud != null) {
                if(pressed) {
                    cloud.setLinearVelocity(new Vector2(20, 0));
                    cloud.setDirection(2);
                } else {
                    cloud.setLinearVelocity(new Vector2(0, 0));
                    cloud.setDirection(0);
                }
            }
        } else if(keyCode == Input.Keys.UP) {
            if(emitter != null) {
                if(pressed)
                    emitter.setLinearVelocity(new Vector2(0, 20));
                else
                    emitter.setLinearVelocity(new Vector2(0, 0));
            }
        } else if(keyCode == Input.Keys.DOWN) {
            if(emitter != null) {
                if(pressed)
                    emitter.setLinearVelocity(new Vector2(0, -20));
                else
                    emitter.setLinearVelocity(new Vector2(0, 0));
            }
        } else if(keyCode == Input.Keys.SPACE) {
            if(pressed) {
                itRain = true;
                if(cloud != null) {
                    cloud.setDirection(-1);
                }
            } else {
                itRain = false;
                if(cloud != null) {
                    cloud.setDirection(0);
                }
            }
        }
    }

    public int getDropsNumber() {
        return dropList.size();
    }

    private void drawDrops() {
        for (Drop drop : dropList) {
            Vector2 v = drop.getLinearVelocity();
            Vector2 p = drop.getPosition();
            float offsetx = v.x / 50f;
            if(offsetx > 10)
                offsetx = 10;
            float offsety = v.y / 50f;
            if(offsety > 10)
                offsety = 10;
            if(!lightVersion)
                sb.draw(dropSprite, p.x - offsetx - dropTextureRadiusQuarter,
                        p.y - offsety - dropTextureRadiusQuarter, dropTextureRadiusHalf, dropTextureRadiusHalf);
            sb.draw(dropSprite, p.x - dropTextureRadiusHalf,
                    p.y - dropTextureRadiusHalf, dropTextureRadius, dropTextureRadius);
            if(!lightVersion)
                sb.draw(dropSprite, p.x + offsetx - dropTextureRadiusQuarter,
                        p.y + offsety - dropTextureRadiusQuarter, dropTextureRadiusHalf, dropTextureRadiusHalf);
        }
    }

    public SpriteBatch sb = new SpriteBatch(175);
    @Override
    public void draw() {
        getCamera().viewportHeight = 480;
        getCamera().viewportWidth = 800;
        getCamera().position.set(getCamera().viewportWidth * .5f, getCamera().viewportHeight * .5f, 0f);
        getCamera().update();
        getSpriteBatch().setProjectionMatrix(getCamera().combined);
        polygonSpriteBatch.setProjectionMatrix(getCamera().combined);
        sb.setProjectionMatrix(getCamera().combined);
        getSpriteBatch().begin();
            getSpriteBatch().draw(backgroundSprite, 0, 0);
        getSpriteBatch().end();

        if(m_fbo != null && useShader) {
            m_fbo.begin();
                sb.begin();
                    Gdx.gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
                    drawDrops();
                sb.end();
            m_fbo.end();

            spriteBatchShadered.begin();
                if(!lightVersion)
                    shader.setUniformf("u_time", time);
                spriteBatchShadered.draw(m_fboRegion, 0, 0, m_fboRegion.getRegionWidth(), m_fboRegion.getRegionHeight());
            spriteBatchShadered.end();
        } else {
            sb.begin();
                drawDrops();
            sb.end();
        }

        getSpriteBatch().begin();
            if(table!=null)
                table.setVisible(false);
            getRoot().draw(getSpriteBatch(), 1);
            if(table!=null)
                table.setVisible(true);
        getSpriteBatch().end();

        if(debugRendererEnabled) {
            liquidHelper.drawDebug();

            getCamera().viewportHeight *= WORLD_TO_BOX;
            getCamera().viewportWidth *= WORLD_TO_BOX;
            getCamera().position.set(getCamera().viewportWidth * .5f, getCamera().viewportHeight * .5f, 0f);
            getCamera().update();
            //getSpriteBatch().setProjectionMatrix(getCamera().combined);

            debugRenderer.render(physicsWorld, getCamera().combined);
        }

        getCamera().viewportHeight = Gdx.graphics.getHeight();
        getCamera().viewportWidth = Gdx.graphics.getWidth();
        getCamera().position.set(getCamera().viewportWidth * .5f, getCamera().viewportHeight * .5f, 0f);
        getCamera().update();
        getSpriteBatch().setProjectionMatrix(getCamera().combined);

        getSpriteBatch().begin();
            if(table != null) {
                table.setPosition(getRoot().getX(), getRoot().getY());
                table.draw(getSpriteBatch(), 1f);
            }
            font.draw(getSpriteBatch(), "FPS: "+Gdx.graphics.getFramesPerSecond(), 10, Gdx.graphics.getHeight()-20);
            font.draw(getSpriteBatch(), "Drops: "+getDropsNumber(), 10, Gdx.graphics.getHeight()-40);
            if(winHint != null)
                font.draw(getSpriteBatch(), "Hint: "+winHint, 10, Gdx.graphics.getHeight()-60);
        getSpriteBatch().end();

        if(debugRendererEnabled)
            Table.drawDebug(this);
    }

    public void setWinHint(String winHint) {
        this.winHint = winHint;
    }

    public PolygonSpriteBatch getPolygonSpriteBatch() {
        return polygonSpriteBatch;
    }
}
