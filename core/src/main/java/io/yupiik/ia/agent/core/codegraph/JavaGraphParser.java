package io.yupiik.ia.agent.core.codegraph;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class JavaGraphParser implements GraphParser {
    private volatile boolean availabilityResolved;
    private volatile boolean compilerAvailable;

    @Override
    public Set<String> extensions() {
        return Set.of("java");
    }

    @Override
    public boolean jdkRequired() {
        return true;
    }

    @Override
    public boolean available() {
        if (!availabilityResolved) {
            compilerAvailable = ToolProvider.getSystemJavaCompiler() != null;
            availabilityResolved = true;
        }
        return compilerAvailable;
    }

    @Override
    public GraphModel parse(final Path file) {
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return GraphModel.empty("java");
        }
        try (final StandardJavaFileManager fm = compiler.getStandardFileManager(new DiagnosticCollector<>(), null, null)) {
            final Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(file.toFile());
            final JavacTask task = (JavacTask) compiler.getTask(null, fm, new DiagnosticCollector<>(),
                    List.of("-proc:none"), null, units);
            final var unit = task.parse().iterator().next();
            final var positions = Trees.instance(task).getSourcePositions();
            final var collector = new Collector(unit, positions);
            unit.accept(collector, null);
            return new GraphModel("java", collector.nodes, collector.edges);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Collector extends TreeScanner<Void, Void> {
        private static final Set<String> PRIMITIVES = Set.of(
                "void", "boolean", "byte", "char", "short", "int", "long", "float", "double");
        private static final Set<String> JAVA_LANG_TYPES = Set.of(
                "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
                "Object", "Class", "Void", "Enum", "Record", "Exception", "RuntimeException", "Throwable",
                "Math", "System", "StringBuilder", "StringBuffer", "Iterable", "Comparable", "CharSequence");
        private final List<GraphModel.Symbol> nodes = new ArrayList<>();
        private final List<GraphModel.Edge> edges = new ArrayList<>();
        private final String fileName;
        private final String packageName;
        private final com.sun.source.tree.LineMap lineMap;
        private final SourcePositions positions;
        private final CompilationUnitTree unit;
        private final Map<String, String> importedTypes = new HashMap<>();
        private final Deque<String> ownerStack = new java.util.ArrayDeque<>();

        private Collector(final CompilationUnitTree unit, final SourcePositions positions) {
            this.fileName = unit.getSourceFile().getName();
            this.packageName = pkg(unit);
            this.lineMap = unit.getLineMap();
            this.positions = positions;
            this.unit = unit;
        }

        private String pkg(final CompilationUnitTree unit) {
            if (unit.getPackageName() != null) {
                return unit.getPackageName().toString();
            }
            final var out = new String[1];
            for (final var t : unit.getTypeDecls()) {
                final var p = t.accept(new com.sun.source.util.SimpleTreeVisitor<Object, Object>() {
                    @Override
                    public Object visitPackage(final PackageTree node, final Object o) {
                        out[0] = node.getPackageName() == null ? "" : node.getPackageName().toString();
                        return out[0];
                    }
                }, null);
                if (p != null) {
                    out[0] = p.toString();
                    break;
                }
            }
            return out[0] == null ? "" : out[0];
        }

        private String fqn(final String packageName, final String name) {
            return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
        }

        private String join(final String parent, final String name) {
            return parent == null || parent.isEmpty() ? name : parent + "." + name;
        }

        private String first(final String name) {
            final var dot = name.indexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        private String last(final String name) {
            final var dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : name;
        }

        private int line(final Tree tree) {
            if (tree == null || lineMap == null || positions == null) {
                return 0;
            }
            final var start = positions.getStartPosition(unit, tree);
            return start < 0 ? 0 : (int) lineMap.getLineNumber(start);
        }

        @Override
        public Void visitImport(final ImportTree node, final Void unused) {
            final var name = node.getQualifiedIdentifier() == null ? "" : node.getQualifiedIdentifier().toString();
            if (node.isStatic()) {
                final var idx = name.lastIndexOf('.');
                if (idx > 0) {
                    importedTypes.putIfAbsent(name.substring(idx + 1), name.substring(0, idx));
                }
            } else {
                final var idx = name.lastIndexOf('.');
                importedTypes.putIfAbsent(idx > 0 ? name.substring(idx + 1) : name, name);
            }
            if (!node.isStatic() && !name.endsWith(".*")) {
                edges.add(new GraphModel.Edge(fqn(packageName, last(name)), name, GraphModel.EdgeType.IMPORTS));
            }
            return super.visitImport(node, unused);
        }

        @Override
        public Void visitClass(final ClassTree node, final Void unused) {
            final var name = node.getSimpleName() == null ? "" : node.getSimpleName().toString();
            final var owner = join(currentOwner(), name);
            ownerStack.push(owner);
            final var kind = node.getKind() == Tree.Kind.INTERFACE ? GraphModel.Kind.INTERFACE
                    : node.getKind() == Tree.Kind.ANNOTATION_TYPE ? GraphModel.Kind.ANNOTATION
                    : node.getKind() == Tree.Kind.ENUM ? GraphModel.Kind.ENUM
                    : GraphModel.Kind.TYPE;
            nodes.add(new GraphModel.Symbol(owner, kind, fileName, line(node), 0, signature(node)));
            if (node.getExtendsClause() != null) {
                relationship(owner, node.getExtendsClause(), GraphModel.EdgeType.EXTENDS);
            }
            if (node.getImplementsClause() != null) {
                for (final var iface : node.getImplementsClause()) {
                    relationship(owner, iface, GraphModel.EdgeType.IMPLEMENTS);
                }
            }
            for (final var member : node.getMembers()) {
                scan(member, unused);
            }
            ownerStack.pop();
            return null;
        }

        @Override
        public Void visitMethod(final MethodTree node, final Void unused) {
            final var methodName = node.getName().toString();
            final var owner = join(currentOwner(), methodName);
            if (!"<init>".equals(methodName) && !"<clinit>".equals(methodName)) {
                nodes.add(new GraphModel.Symbol(owner, GraphModel.Kind.METHOD, fileName, line(node), 0, signature(node)));
            }
            ownerStack.push(owner);
            for (final var param : node.getParameters()) {
                scan(param, unused);
            }
            if (node.getBody() != null) {
                scan(node.getBody(), unused);
            }
            ownerStack.pop();
            return null;
        }

        @Override
        public Void visitVariable(final VariableTree node, final Void unused) {
            if (node.getType() != null) {
                uses(node.getType());
            }
            if (node.getInitializer() != null) {
                scan(node.getInitializer(), unused);
            }
            return null;
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
            final var called = methodName(node.getMethodSelect());
            final var owner = resolveOwner(node.getMethodSelect());
            if (called != null) {
                edges.add(new GraphModel.Edge(currentOwner(), owner == null ? called : owner + "." + called, GraphModel.EdgeType.CALLS));
            }
            scan(node.getArguments(), unused);
            return null;
        }

        @Override
        public Void visitNewClass(final NewClassTree node, final Void unused) {
            final var type = node.getIdentifier() == null ? "" : node.getIdentifier().toString();
            if (!type.isBlank()) {
                final var resolved = resolve(type);
                if (resolved != null) {
                    edges.add(new GraphModel.Edge(currentOwner(), resolved, GraphModel.EdgeType.USES));
                }
            }
            scan(node.getArguments(), unused);
            return null;
        }

        private void relationship(final String owner, final Tree targetType, final GraphModel.EdgeType type) {
            final var text = targetType == null ? "" : targetType.toString();
            if (text.isBlank()) {
                return;
            }
            final var resolved = resolve(text);
            if (resolved != null) {
                edges.add(new GraphModel.Edge(owner, resolved, type));
            }
        }

        private void uses(final Tree type) {
            final var text = type == null ? "" : type.toString();
            if (text.isBlank()) {
                return;
            }
            final var resolved = resolve(text);
            if (resolved != null) {
                edges.add(new GraphModel.Edge(currentOwner(), resolved, GraphModel.EdgeType.USES));
            }
        }

        private String resolve(final String name) {
            if (name.indexOf('.') >= 0) {
                final var simpleFirst = first(name);
                if (isNonSymbol(simpleFirst)) {
                    return null;
                }
                final var imported = importedTypes.get(simpleFirst);
                if (imported != null) {
                    return imported + name.substring(simpleFirst.length());
                }
                if (last(name).isEmpty() || Character.isLowerCase(last(name).charAt(0))) {
                    return null;
                }
                return name;
            }
            if (isNonSymbol(name)) {
                return null;
            }
            final var imported = importedTypes.get(name);
            if (imported != null) {
                return imported;
            }
            return fqn(packageName, name);
        }

        private static boolean isNonSymbol(final String simpleName) {
            return PRIMITIVES.contains(simpleName) || JAVA_LANG_TYPES.contains(simpleName);
        }

        private String resolveOwner(final Tree select) {
            if (select instanceof final MemberSelectTree m) {
                return m.getExpression() == null ? null : resolve(m.getExpression().toString());
            }
            return null;
        }

        private String methodName(final Tree select) {
            if (select instanceof final MemberSelectTree m) {
                return m.getIdentifier() == null ? null : m.getIdentifier().toString();
            }
            if (select instanceof final IdentifierTree i) {
                return i.getName() == null ? null : i.getName().toString();
            }
            return null;
        }

        private String signature(final Tree tree) {
            return tree == null ? "" : tree.toString().lines().findFirst().orElse("").trim();
        }

        private String currentOwner() {
            return ownerStack.isEmpty() ? (packageName == null ? "" : packageName) : ownerStack.peek();
        }
    }
}