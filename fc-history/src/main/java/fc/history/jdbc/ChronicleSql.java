package fc.history.jdbc;

import fc.history.query.EventView;
import fc.world.Chronicle;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 只读 SQL 子集：{@code SELECT ... FROM chronicle [WHERE ...] [ORDER BY ...] [LIMIT ...]}。
 *
 * <p>支持 {@code *}、列清单、{@code COUNT(*)}、{@code AND} 条件、比较与 {@code LIKE}、
 * 单列排序和分页。没有 JOIN、子查询、写入或 DDL——这是编年史查询，不是通用数据库。</p>
 */
public final class ChronicleSql {

    public static final Set<String> COLUMNS = Set.of(
            "day", "date", "type", "category", "location", "actors",
            "cause", "outcome", "summary");

    private ChronicleSql() {
    }

    /** 一列的类型信息。 */
    public record ColumnMeta(String name, int jdbcType, String typeName, String className) {
    }

    /** 查询结果：列定义与行（day 为 Long，其余为 String）。 */
    public record QueryResult(List<ColumnMeta> columns, List<Object[]> rows) {
        public QueryResult {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /** 执行一条只读查询；{@code params} 对应 SQL 中的 {@code ?}。 */
    public static QueryResult execute(Chronicle chronicle, String sql, List<Object> params)
            throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("SQL 不能为空");
        }
        Parser parser = new Parser(tokenize(sql), params == null ? List.of() : params);
        return parser.parse().run(chronicle);
    }

    // ------------------------------------------------------------ 词法

    private enum Kind { WORD, STRING, NUMBER, SYMBOL, PARAM, EOF }

    private record Token(Kind kind, String text, Object value) {
        boolean isWord(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }
    }

    private static List<Token> tokenize(String sql) throws SQLException {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'') {
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            value.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    value.append(ch);
                    i++;
                }
                if (!closed) {
                    throw new SQLException("字符串字面量未闭合");
                }
                tokens.add(new Token(Kind.STRING, value.toString(), value.toString()));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                String text = sql.substring(start, i);
                tokens.add(new Token(Kind.NUMBER, text, Long.parseLong(text)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') {
                        break;
                    }
                    i++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, i),
                        sql.substring(start, i)));
                continue;
            }
            if (c == '?') {
                tokens.add(new Token(Kind.PARAM, "?", null));
                i++;
                continue;
            }
            if (c == '<' || c == '>' || c == '!') {
                if (i + 1 < sql.length()) {
                    String pair = sql.substring(i, i + 2);
                    if (pair.equals("<=") || pair.equals(">=") || pair.equals("<>")
                            || pair.equals("!=")) {
                        tokens.add(new Token(Kind.SYMBOL, pair, pair));
                        i += 2;
                        continue;
                    }
                }
                tokens.add(new Token(Kind.SYMBOL, Character.toString(c), c));
                i++;
                continue;
            }
            if (c == '=' || c == ',' || c == '(' || c == ')' || c == '*' || c == ';') {
                tokens.add(new Token(Kind.SYMBOL, Character.toString(c), c));
                i++;
                continue;
            }
            throw new SQLException("无法识别的字符: " + c);
        }
        tokens.add(new Token(Kind.EOF, "", null));
        return tokens;
    }

    // ------------------------------------------------------------ 语法

    private record Order(String column, boolean descending) {
    }

    private record Condition(String column, String op, Object value) {
    }

    private record SelectItem(String source, String alias) {
    }

    private static final class Query {
        private final List<SelectItem> select;
        private final boolean count;
        private final List<Condition> conditions;
        private final Order order;
        private final int limit;
        private final int offset;

        private Query(List<SelectItem> select, boolean count, List<Condition> conditions,
                      Order order, int limit, int offset) {
            this.select = select;
            this.count = count;
            this.conditions = conditions;
            this.order = order;
            this.limit = limit;
            this.offset = offset;
        }

        QueryResult run(Chronicle chronicle) {
            List<EventView> matched = new ArrayList<>();
            for (var event : chronicle.all()) {
                EventView view = EventView.of(event);
                if (matches(view)) {
                    matched.add(view);
                }
            }
            if (count) {
                List<ColumnMeta> columns = List.of(new ColumnMeta(
                        select.get(0).alias(),
                        Types.INTEGER, "INTEGER", Integer.class.getName()));
                return new QueryResult(columns,
                        List.<Object[]>of(new Object[]{matched.size()}));
            }
            Comparator<EventView> comparator = comparator();
            matched.sort(comparator);
            int from = Math.min(offset, matched.size());
            int to = limit == 0 ? matched.size() : Math.min(matched.size(), from + limit);
            List<EventView> page = matched.subList(from, to);
            List<ColumnMeta> columns = new ArrayList<>();
            for (SelectItem item : select) {
                columns.add(metaFor(item.alias(), item.source()));
            }
            List<Object[]> rows = new ArrayList<>(page.size());
            for (EventView view : page) {
                Object[] row = new Object[select.size()];
                for (int i = 0; i < select.size(); i++) {
                    row[i] = value(view, select.get(i).source());
                }
                rows.add(row);
            }
            return new QueryResult(columns, rows);
        }

        private boolean matches(EventView view) {
            for (Condition condition : conditions) {
                if (!test(view, condition)) {
                    return false;
                }
            }
            return true;
        }

        private Comparator<EventView> comparator() {
            Comparator<EventView> base = (a, b) -> compareValues(
                    value(a, order.column()), value(b, order.column()));
            return order.descending() ? base.reversed() : base;
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final List<Object> params;
        private int index;
        private int paramIndex;

        private Parser(List<Token> tokens, List<Object> params) {
            this.tokens = tokens;
            this.params = params;
        }

        private Query parse() throws SQLException {
            expectWord("SELECT");
            List<SelectItem> select = new ArrayList<>();
            boolean count = false;
            if (peek().isSymbol("*")) {
                index++;
                for (String column : List.of("day", "date", "type", "category",
                        "location", "actors", "cause", "outcome", "summary")) {
                    select.add(new SelectItem(column, column));
                }
            } else {
                while (true) {
                    if (peek().isWord("COUNT")) {
                        index++;
                        expectSymbol("(");
                        expectSymbol("*");
                        expectSymbol(")");
                        count = true;
                        String alias = "count";
                        if (peek().isWord("AS")) {
                            index++;
                            alias = expectWordValue();
                        }
                        select.add(new SelectItem("count", alias));
                    } else {
                        String column = expectWordValue();
                        String normalized = normalizeColumn(column);
                        String alias = normalized;
                        if (peek().isWord("AS")) {
                            index++;
                            alias = expectWordValue();
                        }
                        select.add(new SelectItem(normalized, alias));
                    }
                    if (peek().isSymbol(",")) {
                        index++;
                        continue;
                    }
                    break;
                }
            }
            expectWord("FROM");
            expectWord("CHRONICLE");
            if (peek().isWord("AS")) {
                index++;
                expectWordValue();
            }
            List<Condition> conditions = new ArrayList<>();
            if (peek().isWord("WHERE")) {
                index++;
                do {
                    conditions.add(parseCondition());
                } while (matchWord("AND"));
            }
            Order order = new Order("day", false);
            if (peek().isWord("ORDER")) {
                index++;
                expectWord("BY");
                String column = normalizeColumn(expectWordValue());
                boolean descending = false;
                if (peek().isWord("DESC")) {
                    index++;
                    descending = true;
                } else if (peek().isWord("ASC")) {
                    index++;
                }
                order = new Order(column, descending);
            }
            int limit = 0;
            int offset = 0;
            if (peek().isWord("LIMIT")) {
                index++;
                limit = (int) expectNumber();
                if (peek().isWord("OFFSET")) {
                    index++;
                    offset = (int) expectNumber();
                }
            }
            if (peek().isSymbol(";")) {
                index++;
            }
            if (peek().kind() != Kind.EOF) {
                throw new SQLException("SQL 尾部无法解析: " + peek().text());
            }
            if (count && select.size() != 1) {
                throw new SQLException("COUNT(*) 不能与其他列同时选择");
            }
            if (limit < 0 || offset < 0) {
                throw new SQLException("LIMIT/OFFSET 不得为负");
            }
            return new Query(select, count, conditions, order, limit, offset);
        }

        private Condition parseCondition() throws SQLException {
            String column = normalizeColumn(expectWordValue());
            Token token = peek();
            String op;
            if (token.isWord("LIKE")) {
                index++;
                op = "LIKE";
            } else if (token.kind() == Kind.SYMBOL
                    && (token.text().equals("=") || token.text().equals("!=")
                    || token.text().equals("<>") || token.text().equals("<")
                    || token.text().equals(">") || token.text().equals("<=")
                    || token.text().equals(">="))) {
                index++;
                op = token.text();
            } else {
                throw new SQLException("缺少比较运算符: " + token.text());
            }
            Object value = parseValue();
            return new Condition(column, op, value);
        }

        private Object parseValue() throws SQLException {
            Token token = peek();
            switch (token.kind()) {
                case STRING, NUMBER -> {
                    index++;
                    return token.value();
                }
                case PARAM -> {
                    index++;
                    if (paramIndex >= params.size()) {
                        throw new SQLException("缺少第 " + (paramIndex + 1) + " 个参数");
                    }
                    return params.get(paramIndex++);
                }
                case WORD -> {
                    if (token.isWord("TRUE") || token.isWord("FALSE")) {
                        index++;
                        return Boolean.parseBoolean(token.text());
                    }
                    throw new SQLException("非法字面量: " + token.text());
                }
                default -> throw new SQLException("非法字面量: " + token.text());
            }
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token next() {
            return tokens.get(index++);
        }

        private boolean matchWord(String word) {
            if (peek().isWord(word)) {
                index++;
                return true;
            }
            return false;
        }

        private void expectWord(String word) throws SQLException {
            if (!matchWord(word)) {
                throw new SQLException("期望 " + word + "，实际 " + peek().text());
            }
        }

        private String expectWordValue() throws SQLException {
            Token token = next();
            if (token.kind() != Kind.WORD) {
                throw new SQLException("期望标识符，实际 " + token.text());
            }
            return token.text();
        }

        private void expectSymbol(String symbol) throws SQLException {
            Token token = next();
            if (!token.isSymbol(symbol)) {
                throw new SQLException("期望 " + symbol + "，实际 " + token.text());
            }
        }

        private long expectNumber() throws SQLException {
            Token token = next();
            if (token.kind() != Kind.NUMBER) {
                throw new SQLException("期望数字，实际 " + token.text());
            }
            return (Long) token.value();
        }
    }

    private static String normalizeColumn(String column) throws SQLException {
        String normalized = column.toLowerCase(Locale.ROOT);
        if (!COLUMNS.contains(normalized)) {
            throw new SQLException("未知列: " + column + "（可用: "
                    + String.join(", ", new LinkedHashSet<>(COLUMNS)) + "）");
        }
        return normalized;
    }

    // ------------------------------------------------------------ 求值

    static Object value(EventView view, String column) {
        return switch (column) {
            case "day" -> view.day();
            case "date" -> view.date();
            case "type" -> view.type();
            case "category" -> view.category();
            case "location" -> view.location();
            case "actors" -> view.actorsCsv();
            case "cause" -> view.cause();
            case "outcome" -> view.outcome();
            case "summary" -> view.summary();
            default -> throw new IllegalStateException(column);
        };
    }

    private static boolean test(EventView view, Condition condition) {
        Object actual = value(view, condition.column());
        Object expected = condition.value();
        String op = condition.op();
        if (op.equals("LIKE")) {
            return like(actual, expected);
        }
        int cmp = compareValues(actual, expected);
        return switch (op) {
            case "=" -> cmp == 0;
            case "!=", "<>" -> cmp != 0;
            case "<" -> cmp < 0;
            case ">" -> cmp > 0;
            case "<=" -> cmp <= 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    private static boolean like(Object actual, Object pattern) {
        String value = String.valueOf(actual);
        String raw = String.valueOf(pattern);
        StringBuilder regex = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(Character.toString(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }

    static int compareValues(Object left, Object right) {
        if (left instanceof Long l && right instanceof Number r) {
            return Long.compare(l, r.longValue());
        }
        if (left instanceof Number l && right instanceof Number r) {
            return Long.compare(l.longValue(), r.longValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static ColumnMeta metaFor(String name, String source) {
        if (source.equals("day")) {
            return new ColumnMeta(name, Types.BIGINT, "BIGINT", Long.class.getName());
        }
        return new ColumnMeta(name, Types.VARCHAR, "VARCHAR", String.class.getName());
    }
}
