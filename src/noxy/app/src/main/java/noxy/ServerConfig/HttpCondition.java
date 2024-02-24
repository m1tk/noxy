package noxy.ServerConfig;

import java.lang.reflect.Field;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

public class HttpCondition {
    static ScriptEngine se;

    public Object when;
    public Object redirect;
    public String use_backend;

    public static void init() {
        ScriptEngineManager sem = new ScriptEngineManager();
        se = sem.getEngineByName("groovy");
    }

    public void compile() throws ScriptException {
        String statement = (String)this.when;
        this.when        = ((Compilable) se).compile(statement);
        if (this.redirect != null) {
            statement     = (String)this.redirect;
            this.redirect = ((Compilable) se).compile(statement);
        }
    }

    public boolean evaluate(HttpSettings settings) throws ScriptException, IllegalAccessException {
        if (this.when != null) {
            Bindings bindings = new SimpleBindings();

            for (Field field : settings.getClass().getDeclaredFields()) {
                bindings.put(field.getName(), field.get(settings));
            }

            return (boolean)((CompiledScript)this.when).eval(bindings);
        }
        return true;
    }

    public String evaluate_str(Object val, HttpSettings settings) throws ScriptException, IllegalAccessException {
        Bindings bindings = new SimpleBindings();

        for (Field field : settings.getClass().getDeclaredFields()) {
            bindings.put(field.getName(), field.get(settings));
        }

        return (String)((CompiledScript)val).eval(bindings);
    }
}
