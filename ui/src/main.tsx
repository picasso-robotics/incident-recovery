import React from "react";
import ReactDOM from "react-dom/client";
import "./styles.css";
import { EpisodesView } from "./views/episodes/EpisodesView";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <header className="app-head"><span className="brand">Incident Recovery</span><span className="muted">Episodes</span></header>
    <main className="app-main"><EpisodesView /></main>
  </React.StrictMode>,
);
