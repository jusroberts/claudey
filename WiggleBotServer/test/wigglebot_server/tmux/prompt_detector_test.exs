defmodule WigglebotServer.Tmux.PromptDetectorTest do
  use ExUnit.Case, async: true

  alias WigglebotServer.Tmux.PromptDetector

  test "detects a boxed Claude Code permission menu" do
    pane = """
    some earlier output
    ╭──────────────────────────────────────────────╮
    │ Bash command                                 │
    │   git push -u origin main                    │
    │ Do you want to proceed?                      │
    │ ❯ 1. Yes                                     │
    │   2. Yes, and don't ask again for git push   │
    │   3. No, and tell Claude what to do (esc)    │
    ╰──────────────────────────────────────────────╯
    """

    assert %{question: "Do you want to proceed?", options: options} =
             PromptDetector.detect(pane)

    assert Enum.map(options, & &1.key) == ["1", "2", "3"]
    assert hd(options).label == "Yes"
  end

  test "detects an unboxed numbered menu" do
    pane = """
    Do you want to create todo.txt?
      1. Yes
      2. No
    """

    assert %{question: "Do you want to create todo.txt?", options: [_, _]} =
             PromptDetector.detect(pane)
  end

  test "detects a y/n prompt" do
    assert %{options: [%{key: "y"}, %{key: "n"}]} =
             PromptDetector.detect("Overwrite existing file? (y/n): ")
  end

  test "ignores plain shell output" do
    assert PromptDetector.detect("$ ls\nfoo bar baz\n$\n") == nil
  end

  test "ignores non-consecutive numbered lines" do
    assert PromptDetector.detect("results:\n  3. third\n  7. seventh\n$\n") == nil
  end
end
