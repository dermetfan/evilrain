package com.alex.rain.stages;

import com.alex.rain.helpers.LiquidHelper;
import com.alex.rain.models.*;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.scenes.scene2d.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.*;

import javax.script.*;
import java.io.*;
import java.util.*;

/**
 * @author: Alexander Shubenkov
 * @since: 29.05.13
 */

public class GameWorld extends Stage {
    World physicsWorld = new World(new Vector2(0, -9.8f), true);
    List<Actor> actorList = new ArrayList<Actor>();
    List<Drop> dropList = new ArrayList<Drop>();
    LiquidHelper liquidHelper;
    LuaFunction luaOnCreateFunc;
    LuaFunction luaOnCheckFunc;

    public GameWorld() {
        liquidHelper = new LiquidHelper(dropList);

        ScriptEngineManager sem     = new ScriptEngineManager();
        ScriptEngine        engine  = sem.getEngineByExtension(".lua");
        ScriptEngineFactory factory = engine.getFactory();
        CompiledScript cs;
        String filename = "data/level1.lua";
        try {
            Reader reader = new FileReader(filename);
            cs = ((Compilable)engine).compile(reader);
            SimpleBindings sb = new SimpleBindings();
            cs.eval(sb);
            luaOnCheckFunc = (LuaFunction) sb.get("onCheck");
            luaOnCreateFunc = (LuaFunction) sb.get("onCreate");
        } catch (Exception e) {
            //LogHandler.log.error(e.getMessage(), e);
            System.out.println("error: " + filename);
        }
    }

    public void add(SimpleActor actor) {
        actor.createPhysicsActor(physicsWorld);
        actorList.add(actor);
        addActor(actor);
        if(actor.getType() == SimpleActor.TYPE.DROP)
            dropList.add((Drop)actor);
    }

    public void createWorld() {
        /*for(int i = 0; i < 10; i++) {
            for(int j = 0; j < 10; j++) {
                Drop drop = new Drop(physicsWorld);
                drop.setPosition(new Vector2(i * 15 + 130, j * 15 + 140));
                add(drop);
            }
        }
        add(new Ground(physicsWorld));*/

        LuaValue luaWorld = CoerceJavaToLua.coerce(this);
        luaOnCreateFunc.call(luaWorld);
    }

    @Override
    public void act(float delta) {
        physicsWorld.step(delta*3, 8, 3);
        liquidHelper.applyLiquidConstraint(delta*3);

        LuaValue luaDrop = CoerceJavaToLua.coerce(dropList);
        LuaValue retvals = luaOnCheckFunc.call(luaDrop);
        System.out.println(retvals.tojstring(1));
    }

    public World getPhysicsWorld() {
        return physicsWorld;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        Drop drop = new Drop();
        Random r = new Random();
        int offset = r.nextInt(10) - 10;
        add(drop);
        drop.setPosition(new Vector2(screenX + offset, Gdx.graphics.getHeight() - screenY + offset));

        return true;
    }

    public int getDropsNumber() {
        return dropList.size();
    }
}