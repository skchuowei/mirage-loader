package io.mirage.mgscript.lex;

import io.mirage.mgscript.ScriptError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MGScript 词法分析器：缩进敏感（Python 式 INDENT/DEDENT），但块头冒号可选。
 *
 * <p>三条实现要点，都是踩过 Python 教学经验里最常见的坑：
 * <ol>
 *   <li><b>括号内不产生缩进</b>：多行列表/字典/参数列表靠 nesting 计数屏蔽缩进，否则
 *       {@code [\n  1,\n  2,\n]} 会被判成两个 INDENT；</li>
 *   <li><b>空行与纯注释行不产生 NEWLINE/缩进</b>，但行号照常推进（诊断要对得上编辑器）；</li>
 *   <li><b>字符串插值不在词法阶段展开</b>：整段原文交给 parser 按括号深度切分，
 *       但 {@code \{ } 在这里就转义掉，避免 parser 把字面量大括号当插值。</li>
 * </ol>
 */
public final class Lexer {

    /** 行内插值起始的占位符：{@code \{ } 会被替换成它，parser 阶段再还原为字面 '{'。 */
    public static final char ESCAPED_LBRACE = '\u0001';

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("if", TokenType.KW_IF),
            Map.entry("elif", TokenType.KW_ELIF),
            Map.entry("else", TokenType.KW_ELSE),
            Map.entry("for", TokenType.KW_FOR),
            Map.entry("while", TokenType.KW_WHILE),
            Map.entry("fn", TokenType.KW_FN),
            Map.entry("return", TokenType.KW_RETURN),
            Map.entry("break", TokenType.KW_BREAK),
            Map.entry("continue", TokenType.KW_CONTINUE),
            Map.entry("true", TokenType.KW_TRUE),
            Map.entry("false", TokenType.KW_FALSE),
            Map.entry("none", TokenType.KW_NONE),
            Map.entry("and", TokenType.KW_AND),
            Map.entry("or", TokenType.KW_OR),
            Map.entry("not", TokenType.KW_NOT),
            Map.entry("in", TokenType.KW_IN),
            Map.entry("mod", TokenType.KW_MOD),
            Map.entry("item", TokenType.KW_ITEM),
            Map.entry("block", TokenType.KW_BLOCK),
            Map.entry("on", TokenType.KW_ON),
            Map.entry("every", TokenType.KW_EVERY),
            Map.entry("shared", TokenType.KW_SHARED),
            Map.entry("command", TokenType.KW_COMMAND),
            Map.entry("use", TokenType.KW_USE)
    );

    private final String file;
    private final String[] lines;
    private final List<Token> out = new ArrayList<>();
    private final List<Integer> indents = new ArrayList<>();

    private int pos;
    private int line;
    private int col;
    private int nesting;

    public Lexer(String file, String source) {
        this.file = file;
        this.lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        this.indents.add(0);
    }

    public List<Token> tokenize() {
        for (int i = 0; i < lines.length; i++) {
            line = i + 1;
            String raw = lines[i];
            if (isBlankOrComment(raw)) continue;
            if (nesting == 0) handleIndent(raw);
            pos = 0;
            col = 1;
            while (pos < raw.length()) {
                char c = raw.charAt(pos);
                if (c == ' ' || c == '\t') { advance(c); continue; }
                if (c == '#') break;
                lexToken(raw);
            }
            if (nesting == 0) out.add(new Token(TokenType.NEWLINE, "\n", line, raw.length() + 1));
        }
        // 文件末尾收尾：补 NEWLINE（最后一行可能缺）+ 所有未闭合的 DEDENT
        if (!out.isEmpty() && out.get(out.size() - 1).type() != TokenType.NEWLINE) {
            out.add(new Token(TokenType.NEWLINE, "\n", lines.length, 1));
        }
        while (indents.size() > 1) {
            indents.remove(indents.size() - 1);
            out.add(new Token(TokenType.DEDENT, "", lines.length, 1));
        }
        out.add(new Token(TokenType.EOF, "", lines.length, 1));
        return out;
    }

    // ---- 缩进 ----

    private void handleIndent(String raw) {
        int width = 0;
        while (width < raw.length()) {
            char c = raw.charAt(width);
            if (c == ' ') width++;
            else if (c == '\t') width += 4;
            else break;
        }
        int top = indents.get(indents.size() - 1);
        if (width > top) {
            indents.add(width);
            out.add(new Token(TokenType.INDENT, "", line, 1));
        } else if (width < top) {
            while (indents.size() > 1 && indents.get(indents.size() - 1) > width) {
                indents.remove(indents.size() - 1);
                out.add(new Token(TokenType.DEDENT, "", line, 1));
            }
            if (indents.get(indents.size() - 1) != width) {
                throw ScriptError.at(file, lines, line, 1, 1,
                        "缩进对不上：这一行缩进 " + width + " 格，但外层的块都不是这个缩进",
                        "提示: 本文件统一用 4 个空格缩进；不要混用 Tab（Tab 按 4 格算）");
            }
        }
    }

    // ---- token ----

    private void lexToken(String raw) {
        char c = raw.charAt(pos);
        int startCol = col;
        switch (c) {
            case '(' -> { push(TokenType.LPAREN, "(", startCol); nesting++; consume(1); }
            case ')' -> { push(TokenType.RPAREN, ")", startCol); nesting = Math.max(0, nesting - 1); consume(1); }
            case '[' -> { push(TokenType.LBRACK, "[", startCol); nesting++; consume(1); }
            case ']' -> { push(TokenType.RBRACK, "]", startCol); nesting = Math.max(0, nesting - 1); consume(1); }
            case '{' -> { push(TokenType.LBRACE, "{", startCol); nesting++; consume(1); }
            case '}' -> { push(TokenType.RBRACE, "}", startCol); nesting = Math.max(0, nesting - 1); consume(1); }
            case ',' -> { push(TokenType.COMMA, ",", startCol); consume(1); }
            case ':' -> { push(TokenType.COLON, ":", startCol); consume(1); }
            case '+' -> { push(TokenType.PLUS, "+", startCol); consume(1); }
            case '-' -> { push(TokenType.MINUS, "-", startCol); consume(1); }
            case '*' -> { push(TokenType.STAR, "*", startCol); consume(1); }
            case '/' -> {
                // `//` 是 C 系注释习惯，本语言注释是 `#`（Python 风格）。给一条针对性
                // 提示而不是笼统的"不认识的字符"，否则新手第一行就卡死。
                if (peek(raw, 1) == '/') {
                    throw ScriptError.at(file, lines, line, startCol, 2,
                            "注释请用 # 开头（不是 //）");
                }
                push(TokenType.SLASH, "/", startCol); consume(1);
            }
            case '%' -> { push(TokenType.PERCENT, "%", startCol); consume(1); }
            case '.' -> {
                if (peek(raw, 1) == '.') {
                    if (peek(raw, 2) == '=') { push(TokenType.DOTDOTEQ, "..=", startCol); consume(3); }
                    else { push(TokenType.DOTDOT, "..", startCol); consume(2); }
                } else { push(TokenType.DOT, ".", startCol); consume(1); }
            }
            case '=' -> {
                if (peek(raw, 1) == '=') { push(TokenType.EQ, "==", startCol); consume(2); }
                else { push(TokenType.ASSIGN, "=", startCol); consume(1); }
            }
            case '!' -> {
                if (peek(raw, 1) == '=') { push(TokenType.NE, "!=", startCol); consume(2); }
                else throw ScriptError.at(file, lines, line, startCol, 1,
                        "不认识的字符 '!'（不等于请写 !=，取反请写 not）");
            }
            case '<' -> {
                if (peek(raw, 1) == '=') { push(TokenType.LE, "<=", startCol); consume(2); }
                else { push(TokenType.LT, "<", startCol); consume(1); }
            }
            case '>' -> {
                if (peek(raw, 1) == '=') { push(TokenType.GE, ">=", startCol); consume(2); }
                else { push(TokenType.GT, ">", startCol); consume(1); }
            }
            case '"', '\'' -> lexString(raw);
            default -> {
                if (Character.isDigit(c)) lexNumber(raw);
                else if (isNameStart(c)) lexName(raw);
                else throw ScriptError.at(file, lines, line, startCol, 1,
                        "不认识的字符 '" + c + "'");
            }
        }
    }

    private void lexName(String raw) {
        int start = pos;
        int startCol = col;
        while (pos < raw.length() && isNamePart(raw.charAt(pos))) consume(1);
        String text = raw.substring(start, pos);
        TokenType kw = KEYWORDS.get(text);
        out.add(new Token(kw != null ? kw : TokenType.NAME, text, line, startCol));
    }

    private void lexNumber(String raw) {
        int start = pos;
        int startCol = col;
        while (pos < raw.length() && Character.isDigit(raw.charAt(pos))) consume(1);
        // 小数点：后面不能紧跟第二个 '.'（那是区间 0..10）
        if (pos < raw.length() && raw.charAt(pos) == '.'
                && pos + 1 < raw.length() && Character.isDigit(raw.charAt(pos + 1))) {
            consume(1);
            while (pos < raw.length() && Character.isDigit(raw.charAt(pos))) consume(1);
        }
        String text = raw.substring(start, pos);
        double v = Double.parseDouble(text);
        out.add(new Token(TokenType.NUMBER, text, line, startCol, v));
    }

    private void lexString(String raw) {
        char quote = raw.charAt(pos);
        int startCol = col;
        consume(1);
        StringBuilder sb = new StringBuilder();
        while (pos < raw.length()) {
            char c = raw.charAt(pos);
            if (c == '\\') {
                char n = peek(raw, 1);
                switch (n) {
                    case 'n' -> { sb.append('\n'); consume(2); }
                    case 't' -> { sb.append('\t'); consume(2); }
                    case '\\' -> { sb.append('\\'); consume(2); }
                    case '"' -> { sb.append('"'); consume(2); }
                    case '\'' -> { sb.append('\''); consume(2); }
                    case '{' -> { sb.append(ESCAPED_LBRACE); consume(2); }
                    default -> {
                        throw ScriptError.at(file, lines, line, col, 2,
                                "'\\" + n + "' 不是有效转义",
                                "可用: \\n \\t \\\\ \\\" \\' \\{");
                    }
                }
                continue;
            }
            if (c == quote) {
                consume(1);
                out.add(new Token(TokenType.STRING, sb.toString(), line, startCol, sb.toString()));
                return;
            }
            sb.append(c);
            consume(1);
        }
        throw ScriptError.at(file, lines, line, startCol, 1,
                "字符串没有闭合（直到行尾都没找到 " + quote + "）");
    }

    // ---- 小工具 ----

    private static boolean isBlankOrComment(String raw) {
        String t = raw.strip();
        return t.isEmpty() || t.startsWith("#");
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private char peek(String raw, int ahead) {
        int i = pos + ahead;
        return i < raw.length() ? raw.charAt(i) : '\0';
    }

    private void consume(int n) {
        pos += n;
        col += n;
    }

    private void push(TokenType type, String text, int atCol) {
        out.add(new Token(type, text, line, atCol));
    }

    private void advance(char c) {
        pos++;
        col += (c == '\t') ? 4 : 1;
    }
}
