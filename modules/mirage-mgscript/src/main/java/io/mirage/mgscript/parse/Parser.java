package io.mirage.mgscript.parse;

import io.mirage.mgscript.ScriptError;
import io.mirage.mgscript.ast.Ast;
import io.mirage.mgscript.ast.Ast.Block;
import io.mirage.mgscript.ast.Ast.Expr;
import io.mirage.mgscript.ast.Ast.Stmt;
import io.mirage.mgscript.lex.Lexer;
import io.mirage.mgscript.lex.Token;
import io.mirage.mgscript.lex.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.mirage.mgscript.lex.TokenType.*;

/**
 * MGScript 递归下降解析器。
 *
 * <p>相对 Python 的语法差异（都在这里落地，见 docs/plans/2026-10-31_mgscript-dsl.md §4）：
 * <ul>
 *   <li>块头冒号<b>可选</b>：{@code if x} 与 {@code if x:} 等价；</li>
 *   <li>区间是语法：{@code 0..10} / {@code 0..=10}，没有 {@code range()}；</li>
 *   <li>字符串默认插值：{@code "HP {p.hp}"}，无需 {@code f} 前缀；</li>
 *   <li>{@code item} / {@code block} / {@code on} 是<b>语法关键字</b>而不是库函数，
 *       因此属性名拼错能在解析期就报错（而不是运行期静默无效）。</li>
 * </ul>
 */
public final class Parser {

    private final String file;
    private final String[] sourceLines;
    private final List<Token> tokens;
    private int idx;

    public Parser(String file, String source) {
        this.file = file;
        this.sourceLines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        this.tokens = new Lexer(file, source).tokenize();
    }

    /** 只为插值内嵌表达式构造：直接吃 token 列表，不带源码行。 */
    private Parser(String file, List<Token> tokens) {
        this.file = file;
        this.sourceLines = null;
        this.tokens = tokens;
    }

    /** 解析整个文件，返回顶层语句块。 */
    public Block parseProgram() {
        List<Stmt> stmts = new ArrayList<>();
        skipNewlines();
        while (!check(EOF)) {
            stmts.add(parseStatement());
            skipNewlines();
        }
        return new Block(stmts, 1, 1);
    }

    /** 解析单个表达式（插值用）；解析完必须只剩 NEWLINE/EOF。 */
    private Expr parseSingleExpr() {
        Expr e = parseExpression();
        skipNewlines();
        if (!check(EOF)) {
            throw err(peek(), "插值 {} 里有多余内容 " + peek().describe());
        }
        return e;
    }

    // ---- 语句 ----

    private Stmt parseStatement() {
        Token t = peek();
        return switch (t.type()) {
            case KW_IF -> parseIf();
            case KW_FOR -> parseFor();
            case KW_WHILE -> parseWhile();
            case KW_FN -> parseFnDecl();
            case KW_RETURN -> parseReturn();
            case KW_BREAK -> { advance(); expectStatementEnd(); yield new Ast.Break(t.line(), t.col()); }
            case KW_CONTINUE -> { advance(); expectStatementEnd(); yield new Ast.Continue(t.line(), t.col()); }
            case KW_SHARED -> parseShared();
            case KW_MOD -> parseModDecl();
            case KW_ITEM -> parseItemDecl();
            case KW_BLOCK -> parseBlockDecl();
            case KW_ON -> parseOnDecl();
            case KW_COMMAND, KW_USE -> throw err(t,
                    "'" + t.text() + "' 在语言里保留了位置，但当前版本（P0）还没实现",
                    "提示: P0 只支持 mod / item / block / on；command 与 use 排期在 P1");
            case KW_ELIF, KW_ELSE -> throw err(t,
                    "'" + t.text() + "' 前面没有对应的 if（缩进对不上？）");
            case KW_EVERY -> throw err(t, "'every' 只能写在 on 事件行上，例如: on tick(server) every 20");
            case INDENT -> throw err(t, "缩进多了：这一行不该比上一行更深");
            default -> parseExprOrAssign();
        };
    }

    private Stmt parseExprOrAssign() {
        int line = peek().line();
        int col = peek().col();
        Expr left = parseExpression();
        if (check(ASSIGN)) {
            Token eq = advance();
            if (!(left instanceof Ast.Var || left instanceof Ast.Get || left instanceof Ast.Index)) {
                throw err(eq, "赋值的左边必须是变量、属性或下标（不能给字面量赋值）");
            }
            Expr value = parseExpression();
            expectStatementEnd();
            return new Ast.Assign(left, value, line, col);
        }
        expectStatementEnd();
        return new Ast.ExprStmt(left, line, col);
    }

    private Stmt parseIf() {
        Token kw = advance();
        List<Ast.IfBranch> branches = new ArrayList<>();
        Expr cond = parseExpression();
        Block body = parseBlock();
        branches.add(new Ast.IfBranch(cond, body, kw.line()));

        Block elseBody = null;
        while (check(KW_ELIF)) {
            Token elif = advance();
            Expr c = parseExpression();
            Block b = parseBlock();
            branches.add(new Ast.IfBranch(c, b, elif.line()));
        }
        if (check(KW_ELSE)) {
            advance();
            elseBody = parseBlock();
        }
        return new Ast.If(branches, elseBody, kw.line(), kw.col());
    }

    private Stmt parseFor() {
        Token kw = advance();
        Token name = expect(NAME, "for 后面要跟循环变量名，例如: for p in server.players");
        expect(KW_IN, "for 变量后面要跟 in");
        Expr iter = parseExpression();
        Block body = parseBlock();
        return new Ast.For(name.text(), iter, body, kw.line(), kw.col());
    }

    private Stmt parseWhile() {
        Token kw = advance();
        Expr cond = parseExpression();
        Block body = parseBlock();
        return new Ast.While(cond, body, kw.line(), kw.col());
    }

    private Stmt parseFnDecl() {
        Token kw = advance();
        Token name = expect(NAME, "fn 后面要跟函数名");
        List<Ast.Param> params = parseParams();
        Block body = parseBlock();
        Ast.FnLit fn = new Ast.FnLit(name.text(), params, body, kw.line(), kw.col());
        return new Ast.FnDecl(fn, kw.line(), kw.col());
    }

    private List<Ast.Param> parseParams() {
        List<Ast.Param> params = new ArrayList<>();
        expect(LPAREN, "函数名后面要跟参数列表 ()");
        if (!check(RPAREN)) {
            while (true) {
                Token p = expect(NAME, "参数位置写的是 " + peek().describe() + "，这里需要参数名");
                String typeHint = null;
                if (check(COLON)) {
                    advance();
                    Token t = expect(NAME, "参数类型标注后面需要类型名");
                    typeHint = t.text();
                }
                params.add(new Ast.Param(p.text(), typeHint));
                if (!check(COMMA)) break;
                advance();
            }
        }
        expect(RPAREN, "参数列表没有闭合");
        return params;
    }

    private Stmt parseReturn() {
        Token kw = advance();
        Expr value = null;
        if (!check(NEWLINE) && !check(EOF) && !check(DEDENT)) value = parseExpression();
        expectStatementEnd();
        return new Ast.Return(value, kw.line(), kw.col());
    }

    private Stmt parseShared() {
        Token kw = advance();
        Token name = expect(NAME, "shared 后面要跟变量名");
        expect(ASSIGN, "shared 变量需要初始值，例如: shared counter = 0");
        Expr value = parseExpression();
        expectStatementEnd();
        return new Ast.Shared(name.text(), value, kw.line(), kw.col());
    }

    /** {@code mod "rubycraft" version "1.0.0" requires "x"}：id 之后是同行 key value 列表。 */
    private Stmt parseModDecl() {
        Token kw = advance();
        Token id = expect(STRING, "mod 后面要跟字符串 id，例如: mod \"rubycraft\"");
        Map<String, Expr> props = new LinkedHashMap<>();
        while (!check(NEWLINE) && !check(EOF)) {
            Token key = expect(NAME, "mod 行里 " + peek().describe() + " 不是合法属性名");
            Expr v = parseExpression();
            props.put(key.text(), v);
        }
        expectStatementEnd();
        return new Ast.ModDecl((String) id.value(), props, kw.line(), kw.col());
    }

    private Stmt parseItemDecl() {
        Token kw = advance();
        Token name = expect(STRING, "item 后面要跟字符串注册名，例如: item \"ruby\"");
        Map<String, Expr> props = parsePropBlock();
        return new Ast.ItemDecl((String) name.value(), props, kw.line(), kw.col());
    }

    private Stmt parseBlockDecl() {
        Token kw = advance();
        Token name = expect(STRING, "block 后面要跟字符串注册名，例如: block \"ruby_ore\"");
        Map<String, Expr> props = parsePropBlock();
        return new Ast.BlockDecl((String) name.value(), props, kw.line(), kw.col());
    }

    /** {@code on tick(server) every 20} + 语句块。 */
    private Stmt parseOnDecl() {
        Token kw = advance();
        Token event = expect(NAME, "on 后面要跟事件名，例如: on tick(server)");
        List<String> params = new ArrayList<>();
        if (check(LPAREN)) {
            advance();
            if (!check(RPAREN)) {
                while (true) {
                    Token p = expect(NAME, "事件参数位置写的是 " + peek().describe() + "，这里需要参数名");
                    params.add(p.text());
                    if (!check(COMMA)) break;
                    advance();
                }
            }
            expect(RPAREN, "事件参数列表没有闭合");
        }
        Expr every = null;
        if (check(KW_EVERY)) {
            advance();
            every = parseExpression();
        }
        Block body = parseBlock();
        return new Ast.OnDecl(event.text(), params, every, body, kw.line(), kw.col());
    }

    /** 声明块的属性表：缩进的 {@code key value} 行。没有属性块时返回空表。 */
    private Map<String, Expr> parsePropBlock() {
        Map<String, Expr> props = new LinkedHashMap<>();
        if (check(COLON)) advance();
        if (!check(NEWLINE)) {
            throw err(peek(), "声明行的末尾出现了多余内容 " + peek().describe(),
                    "提示: 属性要换行并缩进写，例如:\n      item \"ruby\"\n          name \"Ruby\"");
        }
        advance();
        if (!check(INDENT)) return props;   // 允许无属性的裸声明
        advance();
        while (!check(DEDENT) && !check(EOF)) {
            skipNewlines();
            if (check(DEDENT) || check(EOF)) break;
            Token key = expect(NAME, "属性名位置写的是 " + peek().describe());
            // 裸标识符当字符串：`rarity rare` / `tool iron_pickaxe` 是 DSL 的常识写法，
            // 否则每个枚举值都要带引号，写起来反而比 YAML 啰嗦。
            Ast.Expr value;
            if (check(NAME) && isAtLineEnd(idx + 1)) {
                Token bare = advance();
                value = new Ast.Literal(bare.text(), bare.line(), bare.col());
            } else {
                value = parseExpression();
            }
            props.put(key.text(), value);
            expectStatementEnd();
            skipNewlines();
        }
        if (check(DEDENT)) advance();
        return props;
    }

    // ---- 块 ----

    private Block parseBlock() {
        int line = peek().line();
        int col = peek().col();
        if (check(COLON)) advance();          // 冒号可选
        if (!check(NEWLINE)) {
            throw err(peek(), "这里需要换行 + 缩进开始一个块，实际是 " + peek().describe());
        }
        advance();
        if (!check(INDENT)) {
            throw err(peek(), "这里需要缩进（块内至少要有一条语句）",
                    "提示: 块内容比上一行多缩进 4 个空格");
        }
        advance();
        List<Stmt> stmts = new ArrayList<>();
        while (!check(DEDENT) && !check(EOF)) {
            stmts.add(parseStatement());
            skipNewlines();
        }
        if (check(DEDENT)) advance();
        return new Block(stmts, line, col);
    }

    // ---- 表达式（优先级从低到高）----

    private Expr parseExpression() {
        return parseOr();
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (check(KW_OR)) {
            Token op = advance();
            Expr right = parseAnd();
            left = new Ast.Binary("or", left, right, op.line(), op.col());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        while (check(KW_AND)) {
            Token op = advance();
            Expr right = parseNot();
            left = new Ast.Binary("and", left, right, op.line(), op.col());
        }
        return left;
    }

    private Expr parseNot() {
        if (check(KW_NOT)) {
            Token op = advance();
            return new Ast.Unary("not", parseNot(), op.line(), op.col());
        }
        return parseComparison();
    }

    private Expr parseComparison() {
        Expr left = parseRange();
        while (true) {
            Token t = peek();
            String op = switch (t.type()) {
                case EQ -> "==";
                case NE -> "!=";
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                case GE -> ">=";
                case KW_IN -> "in";
                default -> null;
            };
            if (op == null && t.type() == KW_NOT) {
                // `x not in list`
                if (idx + 1 < tokens.size() && tokens.get(idx + 1).type() == KW_IN) {
                    advance();
                    Token in = advance();
                    left = new Ast.Binary("not in", left, parseRange(), in.line(), in.col());
                    continue;
                }
                break;
            }
            if (op == null) break;
            advance();
            left = new Ast.Binary(op, left, parseRange(), t.line(), t.col());
        }
        return left;
    }

    private Expr parseRange() {
        Expr left = parseAdditive();
        if (check(DOTDOT) || check(DOTDOTEQ)) {
            Token op = advance();
            Expr right = parseAdditive();
            return new Ast.RangeLit(left, right, op.type() == DOTDOTEQ, op.line(), op.col());
        }
        return left;
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (check(PLUS) || check(MINUS)) {
            Token op = advance();
            Expr right = parseMultiplicative();
            left = new Ast.Binary(op.text(), left, right, op.line(), op.col());
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (check(STAR) || check(SLASH) || check(PERCENT)) {
            Token op = advance();
            Expr right = parseUnary();
            left = new Ast.Binary(op.text(), left, right, op.line(), op.col());
        }
        return left;
    }

    private Expr parseUnary() {
        if (check(MINUS)) {
            Token op = advance();
            return new Ast.Unary("-", parseUnary(), op.line(), op.col());
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr e = parsePrimary();
        while (true) {
            if (check(LPAREN)) {
                Token p = advance();
                List<Expr> args = new ArrayList<>();
                if (!check(RPAREN)) {
                    while (true) {
                        args.add(parseExpression());
                        if (!check(COMMA)) break;
                        advance();
                        if (check(RPAREN)) break;   // 允许尾逗号
                    }
                }
                expect(RPAREN, "调用参数列表没有闭合");
                e = new Ast.Call(e, args, p.line(), p.col());
            } else if (check(DOT)) {
                Token d = advance();
                Token name = expect(NAME, "'.' 后面需要属性名");
                e = new Ast.Get(e, name.text(), d.line(), d.col());
            } else if (check(LBRACK)) {
                Token b = advance();
                Expr i = parseExpression();
                expect(RBRACK, "下标没有闭合");
                e = new Ast.Index(e, i, b.line(), b.col());
            } else {
                break;
            }
        }
        return e;
    }

    private Expr parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER -> { advance(); return new Ast.Literal(t.value(), t.line(), t.col()); }
            case STRING -> { advance(); return parseStr(t); }
            case KW_TRUE -> { advance(); return new Ast.Literal(Boolean.TRUE, t.line(), t.col()); }
            case KW_FALSE -> { advance(); return new Ast.Literal(Boolean.FALSE, t.line(), t.col()); }
            case KW_NONE -> { advance(); return new Ast.Literal(null, t.line(), t.col()); }
            case NAME -> { advance(); return new Ast.Var(t.text(), t.line(), t.col()); }
            case LPAREN -> {
                advance();
                Expr e = parseExpression();
                expect(RPAREN, "括号没有闭合");
                return e;
            }
            case LBRACK -> {
                advance();
                List<Expr> items = new ArrayList<>();
                if (!check(RBRACK)) {
                    while (true) {
                        items.add(parseExpression());
                        if (!check(COMMA)) break;
                        advance();
                        if (check(RBRACK)) break;
                    }
                }
                expect(RBRACK, "列表没有闭合");
                return new Ast.ListLit(items, t.line(), t.col());
            }
            case LBRACE -> {
                advance();
                List<Map.Entry<Expr, Expr>> entries = new ArrayList<>();
                if (!check(RBRACE)) {
                    while (true) {
                        Expr k = parseExpression();
                        expect(COLON, "字典的键后面要跟 ':'");
                        Expr v = parseExpression();
                        entries.add(Map.entry(k, v));
                        if (!check(COMMA)) break;
                        advance();
                        if (check(RBRACE)) break;
                    }
                }
                expect(RBRACE, "字典没有闭合");
                return new Ast.MapLit(entries, t.line(), t.col());
            }
            case KW_FN -> {
                advance();
                String name = null;
                if (check(NAME)) name = advance().text();
                List<Ast.Param> params = parseParams();
                Block body = parseBlock();
                return new Ast.FnLit(name, params, body, t.line(), t.col());
            }
            case INDENT -> throw err(t, "缩进多了：这里不该出现更深的缩进");
            default -> throw err(t, "这里需要表达式，实际读到 " + t.describe());
        }
    }

    /**
     * 字符串插值切分：{@code "HP {p.hp}!"} → {@code ["HP ", Get(p,hp), "!"]}。
     *
     * <p>{@code \{ } 在词法阶段已转成 {@link Lexer#ESCAPED_LBRACE}，这里还原成字面 '{'。
     * 花括号按嵌套深度配对（{@code "{a + b}"} 能正确闭合），所以插值里可以写字典字面量。
     */
    private Expr parseStr(Token tok) {
        String text = (String) tok.value();
        List<Object> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == Lexer.ESCAPED_LBRACE) { lit.append('{'); i++; continue; }
            if (c != '{') { lit.append(c); i++; continue; }

            int depth = 0;
            int start = i + 1;
            int j = i;
            for (; j < text.length(); j++) {
                char d = text.charAt(j);
                if (d == '{') depth++;
                else if (d == '}') {
                    depth--;
                    if (depth == 0) break;
                }
            }
            if (j >= text.length()) {
                // 未闭合的插值不致命：当字面 '{' 处理，并在解析期提示（PM 视角比"整个文件报错"友好）
                throw ScriptError.at(file, sourceLines, tok.line(), tok.col() + i, 1,
                        "插值没有闭合：'}' 少了",
                        "提示: 想输出字面大括号请写 \\{ 与 \\}");
            }
            String exprSrc = text.substring(start, j);
            if (!exprSrc.isBlank()) {
                parts.add(lit.toString());
                lit.setLength(0);
                try {
                    List<Token> sub = new Lexer(file, exprSrc).tokenize();
                    Parser p = new Parser(file, sub);
                    parts.add(p.parseSingleExpr());
                } catch (ScriptError e) {
                    throw ScriptError.at(file, sourceLines, tok.line(), tok.col() + i, j - i + 1,
                            "插值 {" + exprSrc.strip() + "} 里有语法错误：" + e.getMessage());
                }
            }
            i = j + 1;
        }
        if (lit.length() > 0 || parts.isEmpty()) parts.add(lit.toString());
        // 单个纯字面量段落退化成普通字符串，避免求值期多一次拼接
        return new Ast.Str(parts, tok.line(), tok.col());
    }

    // ---- 小工具 ----

    private void expectStatementEnd() {
        if (check(NEWLINE)) { advance(); return; }
        if (check(EOF) || check(DEDENT)) return;
        throw err(peek(), "这一行多余的内容 " + peek().describe(),
                "提示: 一条语句一行，块内容用缩进（不需要分号）");
    }

    private void skipNewlines() {
        while (check(NEWLINE)) advance();
    }

    /** 第 {@code i} 个 token 是否是「行结束」（换行/块结束/文件结束）。 */
    private boolean isAtLineEnd(int i) {
        if (i >= tokens.size()) return true;
        TokenType t = tokens.get(i).type();
        return t == NEWLINE || t == DEDENT || t == EOF;
    }

    private Token peek() {
        return tokens.get(Math.min(idx, tokens.size() - 1));
    }

    private boolean check(TokenType t) {
        return peek().type() == t;
    }

    private Token advance() {
        Token t = peek();
        if (idx < tokens.size() - 1) idx++;
        return t;
    }

    private Token expect(TokenType t, String message) {
        if (!check(t)) throw err(peek(), message + "（实际读到 " + peek().describe() + "）");
        return advance();
    }

    private ScriptError err(Token t, String message, String... hints) {
        return ScriptError.at(file, sourceLines, t.line(), t.col(),
                Math.max(1, t.text() == null ? 1 : t.text().length()), message, hints);
    }
}
