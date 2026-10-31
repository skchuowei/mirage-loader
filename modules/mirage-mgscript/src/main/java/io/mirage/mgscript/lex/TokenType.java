package io.mirage.mgscript.lex;

/** MGScript 词法单元类型。缩进敏感语言，INDENT/DEDENT 是真正的语法符号。 */
public enum TokenType {
    NAME, NUMBER, STRING,
    NEWLINE, INDENT, DEDENT, EOF,

    LPAREN, RPAREN, LBRACK, RBRACK, LBRACE, RBRACE,
    COMMA, COLON, DOT, DOTDOT, DOTDOTEQ, ASSIGN,

    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NE, LT, LE, GT, GE,

    KW_IF, KW_ELIF, KW_ELSE, KW_FOR, KW_WHILE, KW_FN,
    KW_RETURN, KW_BREAK, KW_CONTINUE,
    KW_TRUE, KW_FALSE, KW_NONE,
    KW_AND, KW_OR, KW_NOT, KW_IN,
    KW_MOD, KW_ITEM, KW_BLOCK, KW_ON, KW_EVERY, KW_SHARED, KW_COMMAND, KW_USE,

    /** 顶层声明里 `key value` 形式的裸属性名（如 `stack 64` 的 stack）走 NAME，无需单列。 */
    ;
}
