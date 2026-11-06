from __future__ import annotations

import ast
import math
import operator
from collections.abc import Callable

from docqa_ai.tools.base import ToolContext, ToolError, ToolResult

_MAX_EXPRESSION = 200
_MAX_ABS_RESULT = 1_000_000_000_000.0
_MAX_POWER_EXPONENT = 12.0

_BINARY: dict[type[ast.operator], Callable[[float, float], float]] = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
}
_UNARY: dict[type[ast.unaryop], Callable[[float], float]] = {
    ast.UAdd: operator.pos,
    ast.USub: operator.neg,
}


class CalculatorTool:
    """AST-whitelisted arithmetic only: no names, calls, attributes or comprehensions."""

    name = "calculator"
    source = "builtin"
    description = "计算纯算术表达式，只接受数字、括号与 + - * / // % ** 运算符"
    input_hint = "算术表达式，例如 (2+3)*4，最多 200 字符"

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        expression = argument.strip()
        if not expression or len(expression) > _MAX_EXPRESSION:
            raise ToolError("calculator input is invalid")
        try:
            tree = ast.parse(expression, mode="eval")
        except SyntaxError as exc:
            raise ToolError("calculator input is invalid") from exc
        value = self._evaluate(tree.body)
        if not math.isfinite(value) or abs(value) > _MAX_ABS_RESULT:
            raise ToolError("calculator result is out of range")
        return ToolResult(observation=f"计算结果是 {self._format(value)}")

    def _evaluate(self, node: ast.AST) -> float:
        if isinstance(node, ast.Constant):
            if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
                raise ToolError("calculator only accepts numbers")
            return float(node.value)
        if isinstance(node, ast.BinOp):
            operation = _BINARY.get(type(node.op))
            if operation is None:
                raise ToolError("calculator operator is not allowed")
            left = self._evaluate(node.left)
            right = self._evaluate(node.right)
            if isinstance(node.op, ast.Pow):
                if abs(right) > _MAX_POWER_EXPONENT or abs(left) > 1_000_000:
                    raise ToolError("calculator exponent is out of range")
            try:
                return float(operation(left, right))
            except (ArithmeticError, ValueError) as exc:
                raise ToolError("calculator arithmetic failed") from exc
        if isinstance(node, ast.UnaryOp):
            operation = _UNARY.get(type(node.op))
            if operation is None:
                raise ToolError("calculator operator is not allowed")
            return float(operation(self._evaluate(node.operand)))
        raise ToolError("calculator expression is not allowed")

    @staticmethod
    def _format(value: float) -> str:
        if abs(value) < 1e15 and value == int(value):
            return str(int(value))
        return f"{value:.10g}"
