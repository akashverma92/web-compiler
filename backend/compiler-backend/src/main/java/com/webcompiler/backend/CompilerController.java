package com.webcompiler.backend;

import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class CompilerController {

    @PostMapping("/run")
    public RunResponse run(@RequestBody RunRequest req) {
        final String code = (req.code == null || req.code.isBlank()) ? defaultMain() : sanitizeInput(req.code);
        final String stdinInput = (req.stdin == null) ? "" : req.stdin + "\n";

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("java-run-");
            Path sourceFile = tempDir.resolve("Main.java");
            Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

            // --- Compile ---
            ProcessBuilder compilePb = new ProcessBuilder(
                    "docker", "run", "--rm", "-m", "128m", "--cpus", "0.5",
                    "-v", tempDir.toAbsolutePath() + ":/usr/src/app",
                    "java-runner", "javac", "Main.java"
            ).directory(tempDir.toFile());

            Process compile = compilePb.start();
            boolean compiled = compile.waitFor(15, TimeUnit.SECONDS);
            if (!compiled) {
                compile.destroyForcibly();
                return RunResponse.compileError("⛔ COMPILATION_TIMEOUT");
            }

            if (compile.exitValue() != 0) {
                String err = readAll(compile.getErrorStream());
                return RunResponse.compileError(err);
            }

            // --- Run ---
            ProcessBuilder runPb = new ProcessBuilder(
                    "docker", "run", "-i", "--rm", "-m", "128m", "--cpus", "0.5",
                    "-v", tempDir.toAbsolutePath() + ":/usr/src/app",
                    "-e", "JAVA_OPTS=-Dfile.encoding=UTF-8",
                    "-e", "_JAVA_OPTIONS=-Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8",
                    "java-runner", "java", "-cp", "/usr/src/app", "Main"
            ).directory(tempDir.toFile());

            Process run = runPb.start();

            // Send stdin input if any
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(run.getOutputStream(), StandardCharsets.UTF_8))) {
                if (!stdinInput.isBlank()) {
                    w.write(stdinInput);
                    w.flush();
                    // Ensure the input is properly sent
                    run.getOutputStream().flush();
                }
            }

            // Read stdout and stderr with timeout
            String runOut = futureRead(run.getInputStream(), 30);
            String runErr = futureRead(run.getErrorStream(), 30);

            boolean finished = run.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                run.destroyForcibly();
                return RunResponse.runtimeError("⏱ Program terminated (timeout).");
            }

            int exitCode = run.exitValue();
            String output = (runOut + (runErr.isBlank() ? "" : "\n" + runErr)).strip();
            return RunResponse.ok(output, exitCode);

        } catch (Exception e) {
            return RunResponse.runtimeError("💥 Server error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (tempDir != null) cleanupQuietly(tempDir);
        }
    }

    private static String sanitizeInput(String code) {
        String[] forbidden = {"java.io.File", "java.net", "java.lang.reflect", "System.exit"};
        for (String s : forbidden) if (code.contains(s)) return "// Forbidden code removed\n";
        return code;
    }

    private static String readAll(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String futureRead(InputStream is, int timeoutSeconds) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<String> f = ex.submit(() -> {
                try { 
                    return readAllBuffered(is); 
                } catch (IOException e) { 
                    return ""; 
                }
            });
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        } finally {
            ex.shutdownNow();
        }
    }

    private static String readAllBuffered(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int bytesRead;
        
        // Read all available data immediately
        while (is.available() > 0) {
            bytesRead = is.read(buffer);
            if (bytesRead == -1) break;
            sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }
        
        // Continue reading until no more data
        while ((bytesRead = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }
        
        return sb.toString();
    }

    private static String defaultMain() {
        return """
               import java.util.*;
               public class Main {
                   public static void main(String[] args) {
                       try (Scanner sc = new Scanner(System.in)) {
                           System.out.print("Enter your name: ");
                           String name = sc.nextLine();
                           System.out.println("Hello, " + name + "!");
                           
                           System.out.print("Enter first number: ");
                           int a = sc.nextInt();
                           System.out.print("Enter second number: ");
                           int b = sc.nextInt();
                           System.out.println("Sum: " + (a + b));
                           
                           System.out.print("Enter a number for loop: ");
                           int n = sc.nextInt();
                           for (int i = 1; i <= n; i++) {
                               System.out.print(i + " ");
                           }
                           System.out.println();
                       } catch (NoSuchElementException e) {
                           System.out.println("⚠ No input provided!");
                       }
                   }
               }
               """;
    }

    private static void cleanupQuietly(Path dir) {
        try {
            Files.walk(dir)
                 .sorted((a,b)->b.getNameCount()-a.getNameCount())
                 .forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});
        } catch (IOException ignored){}
    }

    // DTOs
    public static class RunRequest { public String code; public String stdin; }
    public static class RunResponse {
        public boolean ok;
        public String output;
        public Integer exitCode;
        public String stage;

        public static RunResponse ok(String out, int exit){
            RunResponse r = new RunResponse();
            r.ok = true;
            r.output = out;
            r.exitCode = exit;
            r.stage = "run";
            return r;
        }

        public static RunResponse compileError(String msg){
            RunResponse r = new RunResponse();
            r.ok = false;
            r.output = msg;
            r.exitCode = null;
            r.stage = "compile";
            return r;
        }

        public static RunResponse runtimeError(String msg){
            RunResponse r = new RunResponse();
            r.ok = false;
            r.output = msg;
            r.exitCode = null;
            r.stage = "run";
            return r;
        }
    }
}
