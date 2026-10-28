import { createRoot } from "react-dom/client";
import App from "./App";
import { initSettings } from "./lib/state";
import { initTheme } from "./lib/theme";
import "./splicer.css";
import "./app.css";

await initSettings();
initTheme();
createRoot(document.getElementById("root")!).render(<App />);
