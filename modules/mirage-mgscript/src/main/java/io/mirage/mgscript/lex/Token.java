package io.mirage.mgscript.lex;

/**
 * 一个词法单元。{@code line}/{@code col} 均为 1 起，{@code col} 指向该 token 首字符；
 * 报告错误时直接用它画插入符，不需要再回查。
 */
public record Token(TokenType type, String text, int line, int col, Object value) {

    public Token(TokenType type, String text, int line, int col) {
        this(type, text, line, col, null);
    }

    public boolean is(TokenType t) {
        return type == t;
    }

    /** 报错时给人看的名字：关键字/运算符显示字面量，其余显示类别。 */
    public String describe() {
        return switch (type) {
            case NEWLINE -> "换行";
            case INDENT -> "缩进";
            case DEDENT -> "缩进结束";
            case EOF -> "文件结尾";
            case NAME -> "标识符 '" + text + "'";
            case NUMBER -> "数字 '" + text + "'";
            case STRING -> "字符串";
            default -> "'" + text + "'";
        };
    }
}
