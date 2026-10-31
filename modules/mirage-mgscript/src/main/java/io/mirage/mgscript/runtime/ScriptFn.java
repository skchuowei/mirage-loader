package io.mirage.mgscript.runtime;

import io.mirage.mgscript.ast.Ast.Block;

import java.util.List;

/** 脚本函数（闭包）：捕获定义时的 {@link Env}，参数按位置绑定。 */
public record ScriptFn(String name, List<String> params, Block body, Env closure) implements MgCallable {

    @Override
    public Object call(Interp in, List<Object> args, int line, int col) {
        return in.invokeScriptFn(this, args, line, col);
    }
}
