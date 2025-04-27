import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.comments.Comment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.*;

public class DependencyGraph {
    static class Dependency implements Comparable<Dependency> {
        private final int target;
        private final String label;

        Dependency(int target, String label) {
            this.target = target;
            this.label = label;
        }

        int getTarget() {
            return target;
        }

        String getLabel() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Dependency that = (Dependency) o;
            return target == that.target && label.equals(that.label);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + target;
            result = 31 * result + label.hashCode();
            return result;
        }

        @Override
        public int compareTo(Dependency other) {
            return Integer.compare(this.target, other.target);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -cp <classpath> <DependecyGraph> <TargetFileName>");
            return;
        }
        String javaFileName = args[0];
        String inputFile = "../temp/" + javaFileName + ".java";
        CompilationUnit cu = StaticJavaParser.parse(new File(inputFile));

        Map<Integer, String> varDecls = new HashMap<>();
        Map<String, Integer> methodDecls = new HashMap<>();
        Map<String, Integer> constructorDecls = new HashMap<>();
        Map<String, Integer> fieldDecls = new HashMap<>();
        Map<Integer, Set<Dependency>> adjacencyList = new HashMap<>();

        int maxLine = 0;
        for (Node node : cu.findAll(Node.class)) {
            if (node instanceof Comment) {
                continue;
            }
            int line = node.getBegin().get().line;
            maxLine = Math.max(maxLine, line);
            adjacencyList.putIfAbsent(line, new HashSet<>());
        }

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int classLine = clazz.getBegin().get().line;
            clazz.findAll(Node.class).forEach(node -> {
                if (node instanceof Comment) return;
                int nodeLine = node.getBegin().get().line;
                if (nodeLine != classLine) {
                    adjacencyList.computeIfAbsent(nodeLine, k -> new HashSet<>());
                    adjacencyList.get(nodeLine).add(new Dependency(classLine, "class_scope"));
                }
            });
        });

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            int methodLine = method.getBegin().get().line;
            methodDecls.put(method.getNameAsString(), methodLine);
            method.findAll(Node.class).forEach(node -> {
                if (node instanceof Comment) return;
                int nodeLine = node.getBegin().get().line;
                if (nodeLine != methodLine) {
                    adjacencyList.computeIfAbsent(nodeLine, k -> new HashSet<>());
                    adjacencyList.get(nodeLine).add(new Dependency(methodLine, "method_scope"));
                }
            });
        });

        cu.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            int constructorLine = constructor.getBegin().get().line;
            String className = constructor.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse("");
            constructorDecls.put(className, constructorLine);
        });

        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getVariables().forEach(var -> {
                int fieldLine = var.getBegin().get().line;
                fieldDecls.put(var.getNameAsString(), fieldLine);
            });
        });

        cu.findAll(VariableDeclarator.class).forEach(var -> {
            if (!(var.getParentNode().get() instanceof FieldDeclaration)) {
                int line = var.getBegin().get().line;
                varDecls.put(line, var.getNameAsString());
            }
        });

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Map<String, Set<Integer>> possibleDefinitions = new HashMap<>();
            method.findAll(VariableDeclarator.class).forEach(var -> {
                if (!(var.getParentNode().get() instanceof FieldDeclaration)) {
                    String varName = var.getNameAsString();
                    int line = var.getBegin().get().line;
                    possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).add(line);
                }
            });

            List<Node> nodes = new ArrayList<>();
            method.findAll(Node.class).forEach(node -> {
                if (!(node instanceof Comment)) {
                    nodes.add(node);
                }
            });
            nodes.sort((n1, n2) -> Integer.compare(n1.getBegin().get().line, n2.getBegin().get().line));

            Map<Node, Map<String, Set<Integer>>> branchDefinitions = new HashMap<>();

            for (Node node : nodes) {
                int line = node.getBegin().get().line;

                if (node instanceof VariableDeclarator) {
                    VariableDeclarator var = (VariableDeclarator) node;
                    if (!(var.getParentNode().get() instanceof FieldDeclaration)) {
                        String varName = var.getNameAsString();
                        possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).add(line);
                    }
                }

                if (node instanceof AssignExpr) {
                    AssignExpr assign = (AssignExpr) node;
                    String varName = assign.getTarget().toString();
                    if (!fieldDecls.containsKey(varName)) {
                        IfStmt parentIfStmt = assign.findAncestor(IfStmt.class).orElse(null);
                        if (parentIfStmt != null) {
                            branchDefinitions.computeIfAbsent(parentIfStmt, k -> new HashMap<>())
                                    .computeIfAbsent(varName, k -> new HashSet<>()).add(line);
                        } else {
                            possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).clear();
                            possibleDefinitions.get(varName).add(line);
                        }
                    }
                }

                if (node instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) node;
                    int ifLine = ifStmt.getBegin().get().line;
                    Map<String, Set<Integer>> ifBranchDefs = branchDefinitions.getOrDefault(ifStmt, new HashMap<>());

                    // Merge definitions from then branch
                    ifStmt.getThenStmt().findAll(AssignExpr.class).forEach(assign -> {
                        String varName = assign.getTarget().toString();
                        if (!fieldDecls.containsKey(varName)) {
                            int assignLine = assign.getBegin().get().line;
                            possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).add(assignLine);
                        }
                    });

                    // Merge definitions from else branch if present
                    ifStmt.getElseStmt().ifPresent(elseStmt -> {
                        elseStmt.findAll(AssignExpr.class).forEach(assign -> {
                            String varName = assign.getTarget().toString();
                            if (!fieldDecls.containsKey(varName)) {
                                int assignLine = assign.getBegin().get().line;
                                possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).add(assignLine);
                            }
                        });
                    });

                    // Remove declaration dependency if assignments exist
                    for (String varName : possibleDefinitions.keySet()) {
                        Set<Integer> defs = possibleDefinitions.get(varName);
                        if (defs.size() > 1 || (defs.size() == 1 && !defs.contains(varDecls.entrySet().stream()
                                .filter(e -> e.getValue().equals(varName))
                                .map(Map.Entry::getKey)
                                .findFirst().orElse(-1)))) {
                            defs.removeIf(def -> varDecls.containsKey(def) && varDecls.get(def).equals(varName));
                        }
                    }

                    branchDefinitions.remove(ifStmt);
                }

                if (node instanceof NameExpr) {
                    NameExpr nameExpr = (NameExpr) node;
                    String varName = nameExpr.getNameAsString();
                    if (!fieldDecls.containsKey(varName)) {
                        Set<Integer> defLines = possibleDefinitions.getOrDefault(varName, new HashSet<>());
                        for (Integer defLine : defLines) {
                            if (defLine < line) {
                                adjacencyList.computeIfAbsent(line, k -> new HashSet<>());
                                adjacencyList.get(line).add(new Dependency(defLine, "data"));
                            }
                        }
                    }
                }
            }
        });

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Map<String, Integer> methodFieldAssignments = new HashMap<>();
            method.findAll(AssignExpr.class).forEach(assign -> {
                String target = assign.getTarget().toString();
                if (target.startsWith("this.")) {
                    String fieldName = target.substring(5);
                    if (fieldDecls.containsKey(fieldName)) {
                        int assignLine = assign.getBegin().get().line;
                        methodFieldAssignments.put(fieldName, assignLine);
                    }
                }
            });

            method.findAll(NameExpr.class).forEach(nameExpr -> {
                String varName = nameExpr.getNameAsString();
                int usageLine = nameExpr.getBegin().get().line;
                if (fieldDecls.containsKey(varName)) {
                    ClassOrInterfaceDeclaration clazz = nameExpr.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                    if (clazz != null) {
                        String className = clazz.getNameAsString();
                        Integer fieldLine = fieldDecls.get(varName);
                        if (methodFieldAssignments.containsKey(varName)) {
                            int assignLine = methodFieldAssignments.get(varName);
                            if (usageLine != assignLine) {
                                adjacencyList.computeIfAbsent(usageLine, k -> new HashSet<>());
                                adjacencyList.get(usageLine).add(new Dependency(assignLine, "data"));
                            }
                        } else {
                            Integer constructorLine = constructorDecls.get(className);
                            if (constructorLine != null) {
                                ConstructorDeclaration constructor = cu.findAll(ConstructorDeclaration.class).stream()
                                        .filter(c -> c.getBegin().get().line == constructorLine)
                                        .findFirst()
                                        .orElse(null);
                                if (constructor != null) {
                                    boolean foundAssignment = false;
                                    for (AssignExpr assign : constructor.findAll(AssignExpr.class)) {
                                        if (assign.getTarget().toString().equals("this." + varName)) {
                                            int assignLine = assign.getBegin().get().line;
                                            if (usageLine != assignLine) {
                                                adjacencyList.computeIfAbsent(usageLine, k -> new HashSet<>());
                                                adjacencyList.get(usageLine).add(new Dependency(assignLine, "data"));
                                            }
                                            foundAssignment = true;
                                            break;
                                        }
                                    }
                                    if (!foundAssignment && fieldLine != null && usageLine != fieldLine) {
                                        adjacencyList.computeIfAbsent(usageLine, k -> new HashSet<>());
                                        adjacencyList.get(usageLine).add(new Dependency(fieldLine, "data"));
                                    }
                                }
                            } else if (fieldLine != null && usageLine != fieldLine) {
                                adjacencyList.computeIfAbsent(usageLine, k -> new HashSet<>());
                                adjacencyList.get(usageLine).add(new Dependency(fieldLine, "data"));
                            }
                        }
                    }
                }
            });
        });

        cu.findAll(ObjectCreationExpr.class).forEach(creation -> {
            int creationLine = creation.getBegin().get().line;
            String className = creation.getTypeAsString();
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                if (clazz.getNameAsString().equals(className)) {
                    int classLine = clazz.getBegin().get().line;
                    adjacencyList.computeIfAbsent(creationLine, k -> new HashSet<>());
                    adjacencyList.get(creationLine).add(new Dependency(classLine, "class_instantiation"));
                }
            });
            Integer constructorLine = constructorDecls.get(className);
            if (constructorLine != null) {
                adjacencyList.computeIfAbsent(creationLine, k -> new HashSet<>());
                adjacencyList.get(creationLine).add(new Dependency(constructorLine, "constructor_call"));
            }
        });

        cu.findAll(MethodCallExpr.class).forEach(call -> {
            int callerLine = call.getBegin().get().line;
            String methodName = call.getNameAsString();
            Integer methodLine = methodDecls.get(methodName);
            if (methodLine != null && callerLine != methodLine) {
                if (!call.getArguments().isEmpty()) {
                    adjacencyList.computeIfAbsent(callerLine, k -> new HashSet<>());
                    adjacencyList.get(callerLine).add(new Dependency(methodLine, "parameter-in"));
                } else {
                    adjacencyList.computeIfAbsent(callerLine, k -> new HashSet<>());
                    adjacencyList.get(callerLine).add(new Dependency(methodLine, "call"));
                }

                call.getArguments().forEach(arg -> {
                    arg.findAll(NameExpr.class).forEach(param -> {
                        String paramName = param.getNameAsString();
                        MethodDeclaration method = call.findAncestor(MethodDeclaration.class).orElse(null);
                        if (method != null) {
                            Map<String, Set<Integer>> possibleDefinitions = new HashMap<>();
                            method.findAll(VariableDeclarator.class).forEach(var -> {
                                if (!(var.getParentNode().get() instanceof FieldDeclaration)) {
                                    String varName = var.getNameAsString();
                                    int line = var.getBegin().get().line;
                                    possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).add(line);
                                }
                            });

                            List<Node> nodes = new ArrayList<>();
                            method.findAll(Node.class).forEach(node -> {
                                if (!(node instanceof Comment)) {
                                    nodes.add(node);
                                }
                            });
                            nodes.sort((n1, n2) -> Integer.compare(n1.getBegin().get().line, n2.getBegin().get().line));

                            Map<Node, Map<String, Set<Integer>>> branchDefinitions = new HashMap<>();
                            for (Node node : nodes) {
                                int line = node.getBegin().get().line;
                                if (line >= callerLine) break;

                                if (node instanceof AssignExpr) {
                                    AssignExpr assign = (AssignExpr) node;
                                    String varName = assign.getTarget().toString();
                                    if (!fieldDecls.containsKey(varName)) {
                                        IfStmt parentIfStmt = assign.findAncestor(IfStmt.class).orElse(null);
                                        if (parentIfStmt != null) {
                                            branchDefinitions.computeIfAbsent(parentIfStmt, k -> new HashMap<>())
                                                    .computeIfAbsent(varName, k -> new HashSet<>()).add(line);
                                        } else {
                                            possibleDefinitions.computeIfAbsent(varName, k -> new HashSet<>()).clear();
                                            possibleDefinitions.get(varName).add(line);
                                        }
                                    }
                                }

                                if (node instanceof IfStmt) {
                                    IfStmt ifStmt = (IfStmt) node;
                                    Map<String, Set<Integer>> ifBranchDefs = branchDefinitions.getOrDefault(ifStmt, new HashMap<>());
                                    for (String varName : possibleDefinitions.keySet()) {
                                        Set<Integer> thenDefs = ifBranchDefs.getOrDefault(varName, new HashSet<>());
                                        if (!thenDefs.isEmpty()) {
                                            possibleDefinitions.get(varName).addAll(thenDefs);
                                        }
                                    }
                                    if (ifStmt.getElseStmt().isPresent()) {
                                        for (String varName : possibleDefinitions.keySet()) {
                                            Set<Integer> elseDefs = ifBranchDefs.getOrDefault(varName, new HashSet<>());
                                            if (!elseDefs.isEmpty()) {
                                                possibleDefinitions.get(varName).addAll(elseDefs);
                                            }
                                        }
                                    }
                                    branchDefinitions.remove(ifStmt);
                                }
                            }

                            Set<Integer> defLines = possibleDefinitions.getOrDefault(paramName, new HashSet<>());
                            for (Integer defLine : defLines) {
                                if (defLine != callerLine) {
                                    adjacencyList.computeIfAbsent(callerLine, k -> new HashSet<>());
                                    adjacencyList.get(callerLine).add(new Dependency(defLine, "data"));
                                }
                            }
                        }
                    });
                });
            }
        });

        cu.findAll(ReturnStmt.class).forEach(returnStmt -> {
            int returnLine = returnStmt.getBegin().get().line;
            MethodDeclaration method = returnStmt.findAncestor(MethodDeclaration.class).orElse(null);
            if (method != null) {
                String methodName = method.getNameAsString();
                cu.findAll(MethodCallExpr.class).forEach(call -> {
                    if (call.getNameAsString().equals(methodName)) {
                        int callerLine = call.getBegin().get().line;
                        adjacencyList.computeIfAbsent(callerLine, k -> new HashSet<>());
                        adjacencyList.get(callerLine).add(new Dependency(returnLine, "parameter-out"));
                    }
                });
            }
        });

        cu.findAll(IfStmt.class).forEach(ifStmt -> {
            int conditionLine = ifStmt.getCondition().getBegin().get().line;
            ifStmt.getThenStmt().findAll(Node.class).forEach(thenNode -> {
                if (thenNode instanceof Comment) return;
                int thenLine = thenNode.getBegin().get().line;
                if (conditionLine != thenLine) {
                    adjacencyList.computeIfAbsent(thenLine, k -> new HashSet<>());
                    adjacencyList.get(thenLine).add(new Dependency(conditionLine, "control"));
                }
            });
            ifStmt.getElseStmt().ifPresent(elseStmt -> {
                elseStmt.findAll(Node.class).forEach(elseNode -> {
                    if (elseNode instanceof Comment) return;
                    int elseLine = elseNode.getBegin().get().line;
                    if (conditionLine != elseLine) {
                        adjacencyList.computeIfAbsent(elseLine, k -> new HashSet<>());
                        adjacencyList.get(elseLine).add(new Dependency(conditionLine, "control"));
                    }
                });
            });
        });
        cu.findAll(ForStmt.class).forEach(forStmt -> {
            int conditionLine = forStmt.getCompare().get().getBegin().get().line;
            forStmt.getBody().findAll(Node.class).forEach(bodyNode -> {
                if (bodyNode instanceof Comment) return;
                int bodyLine = bodyNode.getBegin().get().line;
                if (conditionLine != bodyLine) {
                    adjacencyList.computeIfAbsent(bodyLine, k -> new HashSet<>());
                    adjacencyList.get(bodyLine).add(new Dependency(conditionLine, "control"));
                }
            });
        });
        cu.findAll(TryStmt.class).forEach(tryStmt -> {
            int tryLine = tryStmt.getBegin().get().line;
            tryStmt.getTryBlock().findAll(Node.class).forEach(tryNode -> {
                if (tryNode instanceof Comment) return;
                int tryBodyLine = tryNode.getBegin().get().line;
                if (tryLine != tryBodyLine) {
                    adjacencyList.computeIfAbsent(tryBodyLine, k -> new HashSet<>());
                    adjacencyList.get(tryBodyLine).add(new Dependency(tryLine, "control"));
                }
            });
            for (com.github.javaparser.ast.stmt.CatchClause catchClause : tryStmt.getCatchClauses()) {
                int catchLine = catchClause.getBegin().get().line;
                catchClause.getBody().findAll(Node.class).forEach(catchNode -> {
                    if (catchNode instanceof Comment) return;
                    int catchBodyLine = catchNode.getBegin().get().line;
                    if (catchLine != catchBodyLine) {
                        adjacencyList.computeIfAbsent(catchBodyLine, k -> new HashSet<>());
                        adjacencyList.get(catchBodyLine).add(new Dependency(catchLine, "control"));
                    }
                });
            }
        });

        JSONObject graph = new JSONObject();
        List<Integer> sortedLines = new ArrayList<>(adjacencyList.keySet());
        Collections.sort(sortedLines);
        for (Integer line : sortedLines) {
            Set<Dependency> deps = adjacencyList.get(line);
            if (!deps.isEmpty()) {
                JSONArray dependencies = new JSONArray();
                List<Dependency> sortedDeps = new ArrayList<>(deps);
                Collections.sort(sortedDeps);
                for (Dependency dep : sortedDeps) {
                    JSONObject dependency = new JSONObject();
                    dependency.put("target", String.valueOf(dep.getTarget()));
                    dependency.put("label", dep.getLabel());
                    dependencies.put(dependency);
                }
                graph.put(String.valueOf(line), dependencies);
            }
        }

        try (FileWriter writer = new FileWriter("../temp/dependencies.json")) {
            writer.write(graph.toString(2));
        }

        System.out.println("Graph saved to dependencies.json");
    }
}