import React from "react";
import ReactDOM from "react-dom/client";
import "@cloudscape-design/global-styles/index.css";
import "./common.css";
import "./cloudscape.css";
import { CloudscapeApp } from "./CloudscapeApp";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <CloudscapeApp />
  </React.StrictMode>,
);
