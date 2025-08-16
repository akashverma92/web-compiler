package com.webcompiler.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class JavaDebugger {
    
    public static class DebugStep {
        public int lineNumber;
        public String operation;
        public Map<String, Object> variables;
        public String output;
        public String explanation;
        public String stackTrace;
        public long timestamp;
        
        public DebugStep(int line, String op, Map<String, Object> vars, String out, String exp) {
            this.lineNumber = line;
            this.operation = op;
            this.variables = new HashMap<>(vars);
            this.output = out;
            this.explanation = exp;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public static class VisualizationData {
        public List<DebugStep> steps;
        public Map<String, Object> finalVariables;
        public String programOutput;
        public List<String> executionPath;
        public Map<Integer, String> lineExplanations;
        
        public VisualizationData() {
            this.steps = new ArrayList<>();
            this.finalVariables = new HashMap<>();
            this.programOutput = "";
            this.executionPath = new ArrayList<>();
            this.lineExplanations = new HashMap<>();
        }
    }
    
    public static VisualizationData debugAndVisualize(String code, String stdin) {
        VisualizationData data = new VisualizationData();
        Map<String, Object> variables = new HashMap<>();
        
        try {
            // Parse the code and extract information
            List<String> lines = Arrays.asList(code.split("\n"));
            data.lineExplanations = analyzeCodeLines(lines);
            
            // Simulate execution step by step
            simulateExecution(lines, variables, data, stdin);
            
        } catch (Exception e) {
            data.steps.add(new DebugStep(0, "ERROR", variables, "", "Error: " + e.getMessage()));
        }
        
        data.finalVariables = new HashMap<>(variables);
        return data;
    }
    
    private static Map<Integer, String> analyzeCodeLines(List<String> lines) {
        Map<Integer, String> explanations = new HashMap<>();
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            int lineNum = i + 1;
            
            if (line.startsWith("import ")) {
                explanations.put(lineNum, "📦 Importing library: " + line.substring(7));
            } else if (line.contains("public class")) {
                explanations.put(lineNum, "🏗️ Defining main class");
            } else if (line.contains("public static void main")) {
                explanations.put(lineNum, "🚀 Program entry point - main method starts");
            } else if (line.contains("Scanner")) {
                explanations.put(lineNum, "📥 Creating Scanner object for input");
            } else if (line.contains("System.out.print")) {
                if (line.contains("System.out.println")) {
                    explanations.put(lineNum, "📤 Printing output with newline");
                } else {
                    explanations.put(lineNum, "📤 Printing output without newline");
                }
            } else if (line.contains("int ") && line.contains("=")) {
                explanations.put(lineNum, "🔢 Declaring and initializing integer variable");
            } else if (line.contains("String ") && line.contains("=")) {
                explanations.put(lineNum, "📝 Declaring and initializing string variable");
            } else if (line.contains("for (")) {
                explanations.put(lineNum, "🔄 Starting for loop");
            } else if (line.contains("while (")) {
                explanations.put(lineNum, "🔄 Starting while loop");
            } else if (line.contains("if (")) {
                explanations.put(lineNum, "❓ Starting if condition");
            } else if (line.contains("else")) {
                explanations.put(lineNum, "❓ Starting else block");
            } else if (line.contains("sc.nextInt()")) {
                explanations.put(lineNum, "📥 Reading integer input from user");
            } else if (line.contains("sc.nextLine()")) {
                explanations.put(lineNum, "📥 Reading string input from user");
            } else if (line.contains("try (")) {
                explanations.put(lineNum, "🛡️ Starting try-with-resources block");
            } else if (line.contains("catch (")) {
                explanations.put(lineNum, "⚠️ Starting exception handling");
            } else if (line.contains("}")) {
                explanations.put(lineNum, "🔚 Closing block");
            } else if (!line.isEmpty() && !line.startsWith("//")) {
                explanations.put(lineNum, "⚙️ Executing statement");
            }
        }
        
        return explanations;
    }
    
    private static void simulateExecution(List<String> lines, Map<String, Object> variables, 
                                        VisualizationData data, String stdin) {
        Scanner inputScanner = new Scanner(stdin);
        StringBuilder output = new StringBuilder();
        boolean waitingForInput = false;
        String currentInputPrompt = "";
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            int lineNum = i + 1;
            
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("import") || 
                line.contains("public class") || line.contains("public static void main")) {
                continue;
            }
            
            try {
                // Simulate different types of operations
                if (line.contains("Scanner sc = new Scanner(System.in)")) {
                    data.steps.add(new DebugStep(lineNum, "CREATE_SCANNER", variables, "", 
                        "Created Scanner object for reading input"));
                    data.executionPath.add("Line " + lineNum + ": Created input scanner");
                }
                else if (line.contains("System.out.print") || line.contains("System.out.println")) {
                    String printContent = extractPrintContent(line);
                    output.append(printContent);
                    if (line.contains("println")) output.append("\n");
                    
                    // Check if this is an input prompt
                    if (printContent.toLowerCase().contains("enter") || 
                        printContent.toLowerCase().contains("input") ||
                        printContent.toLowerCase().contains("name") ||
                        printContent.toLowerCase().contains("number")) {
                        waitingForInput = true;
                        currentInputPrompt = printContent;
                    }
                    
                    data.steps.add(new DebugStep(lineNum, "PRINT", variables, printContent, 
                        "Printed: " + printContent));
                    data.executionPath.add("Line " + lineNum + ": Printed output");
                }
                else if (line.contains("int ") && line.contains("=")) {
                    String varName = extractVariableName(line);
                    Object value = evaluateExpression(line, variables);
                    variables.put(varName, value);
                    
                    data.steps.add(new DebugStep(lineNum, "DECLARE_INT", variables, "", 
                        "Declared integer variable '" + varName + "' = " + value));
                    data.executionPath.add("Line " + lineNum + ": Declared variable " + varName);
                }
                else if (line.contains("String ") && line.contains("=")) {
                    String varName = extractVariableName(line);
                    String value = extractStringValue(line);
                    variables.put(varName, value);
                    
                    data.steps.add(new DebugStep(lineNum, "DECLARE_STRING", variables, "", 
                        "Declared string variable '" + varName + "' = \"" + value + "\""));
                    data.executionPath.add("Line " + lineNum + ": Declared variable " + varName);
                }
                else if (line.contains("sc.nextInt()")) {
                    // Mark as waiting for integer input
                    String varName = extractVariableName(line);
                    data.steps.add(new DebugStep(lineNum, "WAIT_INT_INPUT", variables, "", 
                        "Waiting for integer input for variable '" + varName + "'"));
                    data.executionPath.add("Line " + lineNum + ": Waiting for integer input");
                    waitingForInput = true;
                    currentInputPrompt = "Enter integer value for " + varName;
                }
                else if (line.contains("sc.nextLine()")) {
                    // Mark as waiting for string input
                    String varName = extractVariableName(line);
                    data.steps.add(new DebugStep(lineNum, "WAIT_STRING_INPUT", variables, "", 
                        "Waiting for string input for variable '" + varName + "'"));
                    data.executionPath.add("Line " + lineNum + ": Waiting for string input");
                    waitingForInput = true;
                    currentInputPrompt = "Enter string value for " + varName;
                }
                else if (line.contains("for (")) {
                    data.steps.add(new DebugStep(lineNum, "FOR_LOOP_START", variables, "", 
                        "Starting for loop"));
                    data.executionPath.add("Line " + lineNum + ": Entered for loop");
                    
                    // Simulate loop execution
                    simulateLoopExecution(lines, i, variables, data, inputScanner, output);
                    break; // Skip to end of loop
                }
                else if (line.contains("if (")) {
                    boolean condition = evaluateCondition(line, variables);
                    data.steps.add(new DebugStep(lineNum, "IF_CONDITION", variables, "", 
                        "Evaluated if condition: " + condition));
                    data.executionPath.add("Line " + lineNum + ": If condition = " + condition);
                }
                else if (line.contains("}")) {
                    data.steps.add(new DebugStep(lineNum, "BLOCK_END", variables, "", 
                        "End of code block"));
                    data.executionPath.add("Line " + lineNum + ": End of block");
                }
                
            } catch (Exception e) {
                data.steps.add(new DebugStep(lineNum, "ERROR", variables, "", 
                    "Error at line " + lineNum + ": " + e.getMessage()));
                data.executionPath.add("Line " + lineNum + ": ERROR - " + e.getMessage());
            }
        }
        
        data.programOutput = output.toString();
    }
    
    private static String extractPrintContent(String line) {
        // Extract content between quotes in System.out.print/println
        Pattern pattern = Pattern.compile("System\\.out\\.print(ln)?\\(\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return "output";
    }
    
    private static String extractVariableName(String line) {
        // Extract variable name from declaration
        Pattern pattern = Pattern.compile("(int|String)\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return "unknown";
    }
    
    private static String extractStringValue(String line) {
        // Extract string value from assignment
        Pattern pattern = Pattern.compile("=\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    private static Object evaluateExpression(String line, Map<String, Object> variables) {
        // Simple expression evaluation
        if (line.contains("sc.nextInt()")) {
            return 42; // Default value for simulation
        }
        if (line.contains("+")) {
            return 10; // Default sum for simulation
        }
        return 0;
    }
    
    private static boolean evaluateCondition(String line, Map<String, Object> variables) {
        // Simple condition evaluation
        return true; // Default to true for simulation
    }
    
    private static void simulateLoopExecution(List<String> lines, int startIndex, 
                                            Map<String, Object> variables, VisualizationData data,
                                            Scanner inputScanner, StringBuilder output) {
        // Find loop body and simulate iterations
        int iterations = 3; // Default iterations for simulation
        
        for (int iter = 0; iter < iterations; iter++) {
            data.steps.add(new DebugStep(startIndex + 1, "LOOP_ITERATION", variables, "", 
                "Loop iteration " + (iter + 1) + " of " + iterations));
            data.executionPath.add("Loop iteration " + (iter + 1));
            
            // Simulate loop body execution
            for (int j = startIndex + 1; j < lines.size(); j++) {
                String line = lines.get(j).trim();
                if (line.contains("}")) break;
                
                if (line.contains("System.out.print")) {
                    String content = "Loop output " + (iter + 1);
                    output.append(content);
                    data.steps.add(new DebugStep(j + 1, "LOOP_PRINT", variables, content, 
                        "Loop iteration " + (iter + 1) + " output"));
                }
            }
        }
    }
}
