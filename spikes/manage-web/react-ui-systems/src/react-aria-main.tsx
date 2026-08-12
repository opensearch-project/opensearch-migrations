import React from "react";
import ReactDOM from "react-dom/client";
import "./common.css";
import "./react-aria.css";
import { ReactAriaApp } from "./ReactAriaApp";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ReactAriaApp />
  </React.StrictMode>,
);
