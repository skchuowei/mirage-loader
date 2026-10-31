package io.mirage.mgscript.runtime;

import io.mirage.mgscript.ast.Ast;

import java.util.List;

/** 一切可调用对象。{@code line}/{@code col} 是调用点，用于把错误定位到脚本而不是解释器内部。 */
public interface MgCallable {
    Object call(Interp in, List<Object> args, int line, int col);
}
