package com.webcompiler.backend;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class CompilerWebSocketHandler extends TextWebSocketHandler {

    private static class ProcCtx {
        Process process;
        BufferedWriter stdinWriter;
        Path tempDir;
        ExecutorService streamPool = Executors.newFixedThreadPool(2);
    }

    private final Map<String, ProcCtx> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.sendMessage(new TextMessage("▶ Connected. Click Run to compile & start your program.\n"));
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        if (payload.length() > 10000) {
            safeSend(session, new TextMessage("⚠ Code too large. Maximum allowed size: 10KB\n"));
            return;
        }

        if (payload.startsWith("CODE:")) {
            String code = sanitizeInput(payload.substring(5));
            stopSessionProcess(session);
            compileAndRun(session, code, false, false);
            return;
        }

        if (payload.startsWith("DEBUG_CODE:")) {
            String code = sanitizeInput(payload.substring(11));
            stopSessionProcess(session);
            compileAndRun(session, code, true, false);
            return;
        }

        if (payload.startsWith("VISUALIZE_CODE:")) {
            String code = sanitizeInput(payload.substring(15));
            stopSessionProcess(session);
            compileAndRun(session, code, false, true);
            return;
        }

        // Runtime input
        ProcCtx ctx = sessions.get(session.getId());
        ProcCtx debugCtx = sessions.get(session.getId() + "_debug");
        ProcCtx vizCtx = sessions.get(session.getId() + "_viz");
        
        if (ctx != null && ctx.stdinWriter != null) {
            try {
                ctx.stdinWriter.write(payload);
                ctx.stdinWriter.newLine();
                ctx.stdinWriter.flush();
                // Ensure the input is properly sent to the process
                if (ctx.process != null && ctx.process.isAlive()) {
                    ctx.process.getOutputStream().flush();
                }
            } catch (IOException ignored) {}
        } else if (debugCtx != null) {
            // Handle debug mode input
            handleDebugInput(session, payload, true);
        } else if (vizCtx != null) {
            // Handle visualization mode input
            handleDebugInput(session, payload, false);
        } else {
            safeSend(session, new TextMessage("⚠ No running process. Press Run first.\n"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        stopSessionProcess(session);
    }

    private String sanitizeInput(String code) {
        String[] forbidden = {"java.io.File", "java.net", "java.lang.reflect", "System.exit"};
        for (String s : forbidden) if (code.contains(s)) return "// Forbidden code removed\n";
        return code;
    }

    private void compileAndRun(WebSocketSession session, String code, boolean debug, boolean visualize) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("java-run-");
            Path sourceFile = tempDir.resolve("Main.java");
            Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

            // If debug or visualize mode, use the advanced debugger
            if (debug || visualize) {
                handleDebugOrVisualize(session, code, debug, visualize);
                return;
            }

            ProcessBuilder compilePb = new ProcessBuilder(
                    "docker", "run", "--rm", "-i", "-m", "128m", "--cpus", "0.5",
                    "-v", tempDir.toAbsolutePath() + ":/usr/src/app",
                    "java-runner", "javac", "Main.java"
            ).directory(tempDir.toFile());
            Process compile = compilePb.start();
            boolean compiled = compile.waitFor(20, TimeUnit.SECONDS);
            if (!compiled) {
                compile.destroyForcibly();
                safeSend(session, new TextMessage("⛔ COMPILATION_TIMEOUT\n"));
                cleanupQuietly(tempDir);
                return;
            }
            if (compile.exitValue() != 0) {
                String err = readAll(compile.getErrorStream());
                safeSend(session, new TextMessage("⛔ COMPILATION_ERROR\n" + err));
                cleanupQuietly(tempDir);
                return;
            }

            ProcessBuilder runPb = new ProcessBuilder(
                    "docker", "run", "--rm", "-i", "-m", "128m", "--cpus", "0.5",
                    "-v", tempDir.toAbsolutePath() + ":/usr/src/app",
                    "-e", "JAVA_OPTS=-Dfile.encoding=UTF-8",
                    "-e", "_JAVA_OPTIONS=-Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8",
                    "java-runner", "java", "-cp", "/usr/src/app", "Main"
            ).directory(tempDir.toFile());

            Process proc = runPb.start();
            ProcCtx ctx = new ProcCtx();
            ctx.process = proc;
            ctx.stdinWriter = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            ctx.tempDir = tempDir;
            sessions.put(session.getId(), ctx);

            ctx.streamPool.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[1024];
                    int charsRead;
                    
                    // Read character by character to handle System.out.print() properly
                    while ((charsRead = reader.read(buffer)) != -1) {
                        String chunk = new String(buffer, 0, charsRead);
                        safeSend(session, new TextMessage(chunk));
                    }
                } catch (IOException ignored) {}
            });

            ctx.streamPool.submit(() -> {
                try (InputStream errStream = proc.getErrorStream()) {
                    String err = readAll(errStream);
                    if (!err.isEmpty()) safeSend(session, new TextMessage(err));
                } catch (IOException ignored) {}
            });

            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
                    if (!finished) {
                        proc.destroyForcibly();
                        safeSend(session, new TextMessage("\n⏱ Program terminated (timeout).\n"));
                    } else {
                        safeSend(session, new TextMessage("\n✔ Process exited with code " + proc.exitValue() + "\n"));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    stopSessionProcess(session);
                }
            });

        } catch (Exception e) {
            safeSend(session, new TextMessage("💥 RUNTIME_ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n"));
            cleanupQuietly(tempDir);
        }
    }

    private void handleDebugOrVisualize(WebSocketSession session, String code, boolean debug, boolean visualize) {
        try {
            // Use the JavaDebugger for advanced analysis
            JavaDebugger.VisualizationData data = JavaDebugger.debugAndVisualize(code, "");
            
            if (debug) {
                // Send debug information step by step
                safeSend(session, new TextMessage("🔍 DEBUG MODE: Step-by-step execution analysis\n"));
                safeSend(session, new TextMessage("=".repeat(50) + "\n"));
                
                for (JavaDebugger.DebugStep step : data.steps) {
                    // Send step information
                    ObjectNode stepNode = mapper.createObjectNode();
                    stepNode.put("type", "debugStep");
                    stepNode.put("lineNumber", step.lineNumber);
                    stepNode.put("operation", step.operation);
                    stepNode.put("explanation", step.explanation);
                    stepNode.put("output", step.output);
                    stepNode.set("variables", mapper.valueToTree(step.variables));
                    
                    safeSend(session, new TextMessage(stepNode.toString() + "\n"));
                    
                    // If waiting for input, pause and wait for user input
                    if (step.operation.equals("WAIT_INT_INPUT") || step.operation.equals("WAIT_STRING_INPUT")) {
                        safeSend(session, new TextMessage("⏸️ Waiting for user input...\n"));
                        // Store the session for input handling
                        sessions.put(session.getId() + "_debug", new ProcCtx());
                        return; // Pause execution until input is received
                    }
                    
                    // Add delay for better visualization
                    Thread.sleep(1000);
                }
                
                safeSend(session, new TextMessage("\n📊 FINAL VARIABLES:\n"));
                safeSend(session, new TextMessage(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data.finalVariables) + "\n"));
                
            } else if (visualize) {
                // Send visualization data
                safeSend(session, new TextMessage("📊 VISUALIZATION MODE: Program flow analysis\n"));
                safeSend(session, new TextMessage("=".repeat(50) + "\n"));
                
                // Send line explanations
                ObjectNode lineExplanationsNode = mapper.createObjectNode();
                lineExplanationsNode.put("type", "lineExplanations");
                lineExplanationsNode.set("explanations", mapper.valueToTree(data.lineExplanations));
                safeSend(session, new TextMessage(lineExplanationsNode.toString() + "\n"));
                
                // Send execution path
                ObjectNode executionPathNode = mapper.createObjectNode();
                executionPathNode.put("type", "executionPath");
                executionPathNode.set("path", mapper.valueToTree(data.executionPath));
                safeSend(session, new TextMessage(executionPathNode.toString() + "\n"));
                
                // Send step-by-step visualization
                for (JavaDebugger.DebugStep step : data.steps) {
                    ObjectNode vizNode = mapper.createObjectNode();
                    vizNode.put("type", "visualizationStep");
                    vizNode.put("lineNumber", step.lineNumber);
                    vizNode.put("operation", step.operation);
                    vizNode.put("explanation", step.explanation);
                    vizNode.put("output", step.output);
                    vizNode.set("variables", mapper.valueToTree(step.variables));
                    
                    safeSend(session, new TextMessage(vizNode.toString() + "\n"));
                    
                    // If waiting for input, pause and wait for user input
                    if (step.operation.equals("WAIT_INT_INPUT") || step.operation.equals("WAIT_STRING_INPUT")) {
                        safeSend(session, new TextMessage("⏸️ Waiting for user input...\n"));
                        // Store the session for input handling
                        sessions.put(session.getId() + "_viz", new ProcCtx());
                        return; // Pause execution until input is received
                    }
                    
                    Thread.sleep(800);
                }
                
                safeSend(session, new TextMessage("\n🎯 PROGRAM OUTPUT:\n"));
                safeSend(session, new TextMessage(data.programOutput + "\n"));
            }
            
            safeSend(session, new TextMessage("\n✅ Analysis complete!\n"));
            
        } catch (Exception e) {
            safeSend(session, new TextMessage("💥 ANALYSIS_ERROR: " + e.getMessage() + "\n"));
        }
    }

    private void handleDebugInput(WebSocketSession session, String input, boolean isDebug) {
        try {
            // Send the input back to show what was entered
            safeSend(session, new TextMessage("📥 User input: " + input + "\n"));
            
            // Process the input and continue execution
            ObjectNode inputNode = mapper.createObjectNode();
            inputNode.put("type", "userInput");
            inputNode.put("input", input);
            inputNode.put("mode", isDebug ? "debug" : "visualize");
            
            safeSend(session, new TextMessage(inputNode.toString() + "\n"));
            
            // Continue with next step
            safeSend(session, new TextMessage("▶ Continuing execution...\n"));
            
            // Remove the debug/viz context
            sessions.remove(session.getId() + (isDebug ? "_debug" : "_viz"));
            
        } catch (Exception e) {
            safeSend(session, new TextMessage("💥 INPUT_ERROR: " + e.getMessage() + "\n"));
        }
    }

    private String readAll(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private void stopSessionProcess(WebSocketSession session) {
        ProcCtx ctx = sessions.remove(session.getId());
        if (ctx == null) return;
        try { if (ctx.process != null && ctx.process.isAlive()) ctx.process.destroyForcibly(); } catch (Exception ignored) {}
        try { if (ctx.streamPool != null) ctx.streamPool.shutdownNow(); } catch (Exception ignored) {}
        cleanupQuietly(ctx.tempDir);
    }

    private void cleanupQuietly(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                 .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private void safeSend(WebSocketSession session, TextMessage msg) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(msg);
            }
        } catch (IOException ignored) {}
    }
}
