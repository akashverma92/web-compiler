import { useEffect, useRef, useState } from "react";
import Editor from "@monaco-editor/react";
import type { OnMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor";

type Msg = { kind: "out" | "in"; text: string };
type CompileError = { line: number; column: number; message: string; severity: "error" | "warning" };

// New types for debug and visualization
type DebugStep = {
  lineNumber: number;
  operation: string;
  variables: Record<string, any>;
  output: string;
  explanation: string;
  timestamp: number;
};

type VisualizationData = {
  steps: DebugStep[];
  finalVariables: Record<string, any>;
  programOutput: string;
  executionPath: string[];
  lineExplanations: Record<number, string>;
};

type DebugMessage = {
  type: "debugStep" | "visualizationStep" | "lineExplanations" | "executionPath";
  lineNumber?: number;
  operation?: string;
  explanation?: string;
  output?: string;
  variables?: Record<string, any>;
  explanations?: Record<number, string>;
  path?: string[];
};

const WS_URL = "ws://localhost:8080/ws/run";

const JAVA_KEYWORDS = [
  "abstract","assert","boolean","break","byte","case","catch","char","class",
  "const","continue","default","do","double","else","enum","extends","final",
  "finally","float","for","goto","if","implements","import","instanceof",
  "int","interface","long","native","new","package","private","protected",
  "public","return","short","static","strictfp","super","switch","synchronized",
  "this","throw","throws","transient","try","void","volatile","while"
];

const AUTO_SAVE_KEY = "monaco_temp_code";
const AUTO_SAVE_EXPIRY = 30000;

type Tab = { id: string; title: string; code: string };

const DARK_BG = "#1e1e1e"; 
const LIGHT_BG = "#ffffff"; 

export default function App() {
  const [theme, setTheme] = useState<"dark" | "light">("dark");
  const [tabs, setTabs] = useState<Tab[]>([{ id: "tab-1", title: "Main.java", code: "" }]);
  const [activeTab, setActiveTab] = useState<string>("tab-1");
  const [wsReady, setWsReady] = useState(false);
  const [log, setLog] = useState<Msg[]>([]);
  const [currentInput, setCurrentInput] = useState("");
  const [history, setHistory] = useState<string[]>([]);
  const [histIdx, setHistIdx] = useState(-1);

  const [isDebugging, setIsDebugging] = useState(false);
  const [isVisualizing, setIsVisualizing] = useState(false);
  const [highlightLine, setHighlightLine] = useState<number | null>(null);
  const [variables, setVariables] = useState<Record<string, any>>({});

  // New state for advanced debug/visualization
  const [debugSteps, setDebugSteps] = useState<DebugStep[]>([]);
  const [currentStep, setCurrentStep] = useState<number>(0);
  const [executionPath, setExecutionPath] = useState<string[]>([]);
  const [lineExplanations, setLineExplanations] = useState<Record<number, string>>({});
  const [isStepByStep, setIsStepByStep] = useState(false);
  const [debugSpeed, setDebugSpeed] = useState<number>(1000);

  const wsRef = useRef<WebSocket | null>(null);
  const termRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const autoSaveTimeout = useRef<NodeJS.Timeout | null>(null);
  const decorationsRef = useRef<string[]>([]);

  useEffect(() => {
    const saved = sessionStorage.getItem(AUTO_SAVE_KEY);
    if (saved) {
      const parsed: { code: string; timestamp: number } = JSON.parse(saved);
      if (Date.now() - parsed.timestamp < AUTO_SAVE_EXPIRY) {
        updateTabCode(activeTab, parsed.code);
      }
    }
  }, []);

  const autoSave = (code: string) => {
    if (autoSaveTimeout.current) clearTimeout(autoSaveTimeout.current);
    autoSaveTimeout.current = setTimeout(() => {
      sessionStorage.setItem(AUTO_SAVE_KEY, JSON.stringify({ code, timestamp: Date.now() }));
    }, 500);
  };

  const updateTabCode = (id: string, newCode: string) => {
    setTabs(prev => prev.map(t => t.id === id ? { ...t, code: newCode } : t));
  };

  const currentTab = tabs.find(t => t.id === activeTab)!;

  useEffect(() => {
    connectWS();
    return () => { wsRef.current?.close(); };
  }, []);

  const connectWS = () => {
    try {
      const ws = new WebSocket(WS_URL);
      ws.onopen = () => setWsReady(true);
      ws.onclose = () => setWsReady(false);
      ws.onerror = () => setWsReady(false);
      ws.onmessage = (e) => {
        try {
          const msg = JSON.parse(e.data as string);

          if (msg.type === "compileError") {
            const markers: monaco.editor.IMarkerData[] = msg.errors.map((err: CompileError) => ({
              startLineNumber: err.line,
              startColumn: err.column,
              endLineNumber: err.line,
              endColumn: err.column + 1,
              message: err.message,
              severity: err.severity === "error" ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning
            }));
            monaco.editor.setModelMarkers(editorRef.current!.getModel()!, "javac", markers);
          } 
          else if (msg.type === "runtimeOutput" || msg.type === "runtimeError") {
            setLog(prev => [...prev, { kind: "out", text: msg.text }]);
          } 
          else if (msg.type === "debugStep") {
            handleDebugStep(msg as DebugMessage);
          }
          else if (msg.type === "visualizationStep") {
            handleVisualizationStep(msg as DebugMessage);
          }
          else if (msg.type === "userInput") {
            handleUserInput(msg as DebugMessage);
          }
          else if (msg.type === "lineExplanations") {
            setLineExplanations(msg.explanations || {});
          }
          else if (msg.type === "executionPath") {
            setExecutionPath(msg.path || []);
          }
          else if (msg.type === "step") {
            setHighlightLine(msg.line);
            setVariables(msg.variables || {});
          } 
          else if (msg.type === "visualize") {
            setHighlightLine(msg.line);
            setVariables(msg.variables || {});
            setLog(prev => [...prev, { kind: "out", text: msg.message || "" }]);
          }

        } catch {
          setLog(prev => [...prev, { kind: "out", text: String(e.data) }]);
        }
      };
      wsRef.current = ws;
    } catch {
      setWsReady(false);
    }
  };

  const send = (data: string) => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setWsReady(false);
      return;
    }
    ws.send(data);
  };

  const handleDebugStep = (msg: DebugMessage) => {
    const step: DebugStep = {
      lineNumber: msg.lineNumber || 0,
      operation: msg.operation || "",
      variables: msg.variables || {},
      output: msg.output || "",
      explanation: msg.explanation || "",
      timestamp: Date.now()
    };
    
    setDebugSteps(prev => [...prev, step]);
    setHighlightLine(step.lineNumber);
    setVariables(step.variables);
    setCurrentStep(prev => prev + 1);
    
    // Add to log
    setLog(prev => [...prev, { 
      kind: "out", 
      text: `🔍 Step ${currentStep + 1}: ${step.explanation}\n` 
    }]);
    
    // If waiting for input, show input prompt
    if (step.operation === "WAIT_INT_INPUT" || step.operation === "WAIT_STRING_INPUT") {
      setLog(prev => [...prev, { 
        kind: "out", 
        text: `⏸️ Waiting for user input... (${step.operation === "WAIT_INT_INPUT" ? "integer" : "string"})\n` 
      }]);
      // Focus on terminal for input
      termRef.current?.focus();
    }
  };

  const handleVisualizationStep = (msg: DebugMessage) => {
    const step: DebugStep = {
      lineNumber: msg.lineNumber || 0,
      operation: msg.operation || "",
      variables: msg.variables || {},
      output: msg.output || "",
      explanation: msg.explanation || "",
      timestamp: Date.now()
    };
    
    setDebugSteps(prev => [...prev, step]);
    setHighlightLine(step.lineNumber);
    setVariables(step.variables);
    setCurrentStep(prev => prev + 1);
    
    // Add to log
    setLog(prev => [...prev, { 
      kind: "out", 
      text: `📊 ${step.explanation}\n` 
    }]);
    
    // If waiting for input, show input prompt
    if (step.operation === "WAIT_INT_INPUT" || step.operation === "WAIT_STRING_INPUT") {
      setLog(prev => [...prev, { 
        kind: "out", 
        text: `⏸️ Waiting for user input... (${step.operation === "WAIT_INT_INPUT" ? "integer" : "string"})\n` 
      }]);
      // Focus on terminal for input
      termRef.current?.focus();
    }
  };

  const handleUserInput = (msg: DebugMessage) => {
    setCurrentInput(msg.output || "");
    setLog(prev => [...prev, { kind: "in", text: msg.output || "" }]);
    setHistIdx(-1); // Reset history index when user input is received
  };

  const run = () => {
    setLog([]);
    setCurrentInput("");
    setHistIdx(-1);
    monaco.editor.setModelMarkers(editorRef.current!.getModel()!, "javac", []);
    setIsDebugging(false);
    setIsVisualizing(false);
    setHighlightLine(null);
    setVariables({});
    setDebugSteps([]);
    setCurrentStep(0);
    setExecutionPath([]);
    setLineExplanations({});
    setIsStepByStep(false);
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      connectWS();
      setTimeout(() => send("CODE:" + currentTab.code), 200);
    } else {
      send("CODE:" + currentTab.code);
    }
    termRef.current?.focus();
  };

  const debugRun = () => {
    setLog([]);
    setCurrentInput("");
    setHistIdx(-1);
    monaco.editor.setModelMarkers(editorRef.current!.getModel()!, "javac", []);
    setIsDebugging(true);
    setIsVisualizing(false);
    setHighlightLine(null);
    setVariables({});
    setDebugSteps([]);
    setCurrentStep(0);
    setExecutionPath([]);
    setLineExplanations({});
    setIsStepByStep(true);
    send("DEBUG_CODE:" + currentTab.code);
  };

  const visualizeRun = () => {
    setLog([]);
    setCurrentInput("");
    setHistIdx(-1);
    monaco.editor.setModelMarkers(editorRef.current!.getModel()!, "javac", []);
    setIsVisualizing(true);
    setIsDebugging(false);
    setHighlightLine(null);
    setVariables({});
    setDebugSteps([]);
    setCurrentStep(0);
    setExecutionPath([]);
    setLineExplanations({});
    setIsStepByStep(true);
    send("VISUALIZE_CODE:" + currentTab.code);
  };

  const handleTerminalKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    e.preventDefault();
    const key = e.key;
    if (key === "Enter") {
      const line = currentInput;
      setLog(prev => [...prev, { kind: "in", text: line + "\n" }]);
      if (line.trim()) setHistory(h => [line, ...h]);
      setCurrentInput("");
      setHistIdx(-1);
      send(line + "\n");
      return;
    }
    if (key === "Backspace") setCurrentInput(s => (s.length ? s.slice(0, -1) : s));
    if (key === "ArrowUp") {
      setHistIdx(idx => {
        const next = Math.min(idx + 1, history.length - 1);
        setCurrentInput(history[next] ?? (history[0] ?? ""));
        return next;
      });
    }
    if (key === "ArrowDown") {
      setHistIdx(idx => {
        const next = Math.max(idx - 1, -1);
        setCurrentInput(next === -1 ? "" : (history[next] ?? ""));
        return next;
      });
    }
    if (key === "Tab") setCurrentInput(s => s + "  ");
    if (key.length === 1) setCurrentInput(s => s + key);
  };

  const onEditorMount: OnMount = (editor, monacoIns) => {
    editorRef.current = editor;

    monacoIns.editor.defineTheme("quiet-dark", {
      base: "vs-dark",
      inherit: true,
      rules: [],
      colors: { "editor.background": DARK_BG }
    });
    monacoIns.editor.defineTheme("quiet-light", {
      base: "vs",
      inherit: true,
      rules: [],
      colors: { "editor.background": LIGHT_BG }
    });

    monacoIns.editor.setTheme(theme === "dark" ? "quiet-dark" : "quiet-light");

    monaco.languages.registerCompletionItemProvider("java", {
      provideCompletionItems: () => {
        const suggestions = JAVA_KEYWORDS.map(kw => ({
          label: kw,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: kw
        }));
        return { suggestions };
      }
    });
  };

  useEffect(() => {
    monaco.editor.setTheme(theme === "dark" ? "quiet-dark" : "quiet-light");
  }, [theme]);

  useEffect(() => {
    if (!editorRef.current) return;
    decorationsRef.current = editorRef.current.deltaDecorations(
      decorationsRef.current,
      highlightLine !== null ? [{
        range: new monaco.Range(highlightLine, 1, highlightLine, 1),
        options: { isWholeLine: true, className: "highlightLine" }
      }] : []
    );
  }, [highlightLine]);

  const formatCode = () => {
    editorRef.current?.getAction("editor.action.formatDocument").run();
  };

  const addTab = () => {
    const id = `tab-${Date.now()}`;
    setTabs(prev => [...prev, { id, title: "New.java", code: "" }]);
    setActiveTab(id);
  };

  const closeTab = (id: string) => {
    setTabs(prev => prev.filter(t => t.id !== id));
    if (activeTab === id && tabs.length > 1) setActiveTab(tabs[0].id);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", minHeight: "100vh" }}>
      <header style={{ padding: "12px 16px", display: "flex", alignItems: "center", gap: 12 }}>
        <strong>Java Online IDE</strong>
        <button onClick={run}>▶ Run</button>
        <button onClick={debugRun}>🐞 Debug</button>
        <button onClick={visualizeRun}>📊 Visualize</button>
        <button onClick={() => setTheme(t => t === "dark" ? "light" : "dark")}>
          {theme === "dark" ? "🌤 Light" : "🌙 Dark"}
        </button>
        <button onClick={formatCode}>🖋 Format</button>
        <button onClick={addTab}>＋ New Tab</button>
        <span style={{ marginLeft: "auto", fontSize: 12 }}>{wsReady ? "● Connected" : "○ Disconnected"}</span>
      </header>

      <div style={{ display: "flex", flexDirection: "row", gap: 6, padding: "0 12px" }}>
        {tabs.map(t => (
          <div key={t.id} style={{ padding: 6, borderBottom: t.id === activeTab ? "2px solid #38bdf8" : "none", cursor: "pointer", display: "flex", alignItems: "center", gap: 4 }}>
            <span onClick={() => setActiveTab(t.id)}>{t.title}</span>
            {tabs.length > 1 && <span onClick={() => closeTab(t.id)} style={{ color: "red", cursor: "pointer" }}>×</span>}
          </div>
        ))}
      </div>

      <div style={{ display: "flex", flex: 1, minHeight: 0, gap: 12, padding: 12 }}>
        <div className="glow-container" style={{ flex: "0 0 70vw", maxWidth: "67vw" }}>
          <Editor
            height="calc(108vh - 200px)"
            defaultLanguage="java"
            value={currentTab.code}
            onChange={v => {
              const newCode = v || "";
              updateTabCode(currentTab.id, newCode);
              autoSave(newCode);
            }}
            onMount={onEditorMount}
            theme={theme === "dark" ? "quiet-dark" : "quiet-light"}
            options={{
              fontSize: 14,
              automaticLayout: true,
              minimap: { enabled: false },
              tabSize: 4,
              insertSpaces: true,
              formatOnType: true,
              formatOnPaste: true
            }}
          />
        </div>

        <div style={{ flex: "0 0 44vw", maxWidth: "30vw", display: "flex", flexDirection: "column" }}>
          <div
            ref={termRef}
            tabIndex={0}
            onKeyDown={handleTerminalKey}
            className="glow-container"
            style={{
              background: theme === "dark" ? DARK_BG : LIGHT_BG,
              color: theme === "dark" ? "#ffffff" : "#000000",
              border: "1px solid gray",
              borderRadius: 10,
              padding: 8,
              fontFamily: "monospace",
              fontSize: 13,
              height: "60%",
              overflowY: "auto",
              whiteSpace: "pre-wrap"
            }}
          >
            {log.map((m, i) => <span key={i} style={{ color: m.kind === "in" ? "#38bdf8" : "#0ea5a5" }}>{m.text}</span>)}
            <span style={{ color: "#38bdf8" }}>{currentInput}</span>
            <span style={{ display: "inline-block", width: 7, height: 14, background: "#38bdf8", marginLeft: 2, verticalAlign: "middle", animation: "blink 1s step-end infinite" }} />
          </div>
          
          {(isDebugging || isVisualizing) && (
            <div style={{
              marginTop: 6,
              padding: 8,
              border: "1px solid gray",
              borderRadius: 6,
              height: "40%",
              overflowY: "auto",
              fontSize: 12,
              background: theme === "dark" ? "#1c1f26" : "#f5f5f5"
            }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
                <strong>{isDebugging ? "🔍 Debug Info" : "📊 Visualization"}</strong>
                {isStepByStep && (
                  <div style={{ display: "flex", gap: 4 }}>
                    <button 
                      onClick={() => setDebugSpeed(prev => Math.max(200, prev - 200))}
                      style={{ padding: "2px 6px", fontSize: 10 }}
                    >
                      ⏩ Faster
                    </button>
                    <button 
                      onClick={() => setDebugSpeed(prev => Math.min(3000, prev + 200))}
                      style={{ padding: "2px 6px", fontSize: 10 }}
                    >
                      ⏪ Slower
                    </button>
                  </div>
                )}
              </div>
              
              <div style={{ marginBottom: 8 }}>
                <strong>Current Step:</strong> {currentStep} / {debugSteps.length}
              </div>
              
              <div style={{ marginBottom: 8 }}>
                <strong>Variables:</strong>
                <pre style={{ fontSize: 11, margin: 4, padding: 4, background: theme === "dark" ? "#2d3748" : "#e2e8f0", borderRadius: 4 }}>
                  {JSON.stringify(variables, null, 2)}
                </pre>
              </div>
              
              {executionPath.length > 0 && (
                <div style={{ marginBottom: 8 }}>
                  <strong>Execution Path:</strong>
                  <div style={{ fontSize: 11, maxHeight: 60, overflowY: "auto" }}>
                    {executionPath.map((step, i) => (
                      <div key={i} style={{ padding: "1px 0", color: i === currentStep - 1 ? "#38bdf8" : "inherit" }}>
                        {i + 1}. {step}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              
              {Object.keys(lineExplanations).length > 0 && (
                <div>
                  <strong>Line Explanations:</strong>
                  <div style={{ fontSize: 11, maxHeight: 80, overflowY: "auto" }}>
                    {Object.entries(lineExplanations).map(([line, explanation]) => (
                      <div key={line} style={{ padding: "1px 0" }}>
                        <span style={{ color: "#38bdf8" }}>Line {line}:</span> {explanation}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes blink {50% { opacity: 0; }}
        .highlightLine { background-color: rgba(56, 189, 248, 0.3) !important; }

        /* Always-on subtle glow */
        .glow-container {
          box-shadow: 0 0 6px rgba(56, 189, 248, 0.4);
          transition: box-shadow 0.2s ease-in-out;
          border-radius: 10px;
        }

        /* Stronger, pulsing glow when focused/active */
        .glow-container:focus-within {
          animation: pulseGlow 1.5s infinite ease-in-out;
        }

        @keyframes pulseGlow {
          0% {
            box-shadow: 0 0 8px rgba(56, 189, 248, 0.8), 0 0 15px rgba(56, 189, 248, 0.6);
          }
          50% {
            box-shadow: 0 0 14px rgba(56, 189, 248, 1), 0 0 25px rgba(56, 189, 248, 0.8);
          }
          100% {
            box-shadow: 0 0 8px rgba(56, 189, 248, 0.8), 0 0 15px rgba(56, 189, 248, 0.6);
          }
        }
      `}</style>
    </div>
  );
}
