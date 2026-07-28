"""The tool registry.

Two things live here that deliberately do not live in the model's context:

1. **What tools exist.** The model can only name tools the registry declares; anything
   else fails closed with "unknown tool".
2. **Which tools mutate.** The ``mutating`` flag is set in code and reviewed like code.
   It is never inferred from the model's intent, its confidence, or its own claim about
   what a call does. ADR-007 depends on that: if the model could classify its own calls,
   the approval gate would be advisory.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    handler: Callable[..., str]
    mutating: bool = False
    args: dict[str, str] = field(default_factory=dict)

    def signature(self) -> str:
        params = ", ".join(f"{k}:{v}" for k, v in self.args.items())
        return f"{self.name}({params})"


class UnknownTool(KeyError):
    pass


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        self._tools[tool.name] = tool

    def get(self, name: str) -> Tool:
        try:
            return self._tools[name]
        except KeyError as exc:
            raise UnknownTool(name) from exc

    def names(self) -> list[str]:
        return sorted(self._tools)

    def all(self) -> list[Tool]:
        return [self._tools[n] for n in self.names()]

    def is_mutating(self, name: str) -> bool:
        return self.get(name).mutating

    def describe(self) -> str:
        """Compact catalogue for the system prompt, which the gateway caps at 2000 chars."""
        lines = []
        for tool in self.all():
            marker = "!" if tool.mutating else "-"
            lines.append(f"{marker} {tool.signature()} — {tool.description}")
        return "\n".join(lines)
