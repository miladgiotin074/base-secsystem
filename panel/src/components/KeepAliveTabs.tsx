"use client";

import type { ReactNode } from "react";

type Pane<T extends string> = {
  id: T;
  node: ReactNode;
};

export function KeepAliveTabs<T extends string>({
  active,
  panes,
}: {
  active: T;
  panes: Pane<T>[];
}) {
  return (
    <div className="md-main">
      {panes.map((pane) => {
        const on = pane.id === active;
        return (
          <div
            key={pane.id}
            className={`md-pane${on ? " md-pane--on" : ""}`}
            aria-hidden={!on}
            inert={!on}
          >
            {pane.node}
          </div>
        );
      })}
    </div>
  );
}
