defmodule WigglebotServer.Tmux.PromptDetector do
  @moduledoc """
  Heuristic detection of interactive prompts (Claude Code permission menus,
  y/n questions) in a tmux pane snapshot, so the client can render tappable
  answer buttons instead of making the user type "1<Enter>".
  """

  @tail_lines 40

  @doc """
  Returns `%{question: String.t() | nil, options: [%{key: k, label: l}]}`
  when the pane tail looks like it is waiting on a choice, else `nil`.

  `key` is what to send: a digit string (type + Enter), "y"/"n", or "Enter".
  """
  def detect(content) when is_binary(content) do
    lines =
      content
      |> String.split("\n")
      |> Enum.map(&strip_box/1)
      |> drop_trailing_blanks()
      |> Enum.take(-@tail_lines)

    detect_numbered(lines) || detect_yes_no(lines)
  end

  def detect(_), do: nil

  # ── Numbered menu (Claude Code permission dialogs) ──────────────────────────

  defp detect_numbered(lines) do
    options =
      lines
      |> Enum.reverse()
      |> Enum.take_while(&(numbered_option(&1) != nil or blank?(&1)))
      |> Enum.reject(&blank?/1)
      |> Enum.reverse()
      |> Enum.map(&numbered_option/1)

    numbers = Enum.map(options, & &1.key)

    if length(options) >= 2 and numbers == Enum.map(1..length(options), &Integer.to_string/1) do
      %{question: question_above(lines, length(options)), options: options}
    else
      nil
    end
  end

  defp numbered_option(line) do
    case Regex.run(~r/^\s*(?:❯|>)?\s*(\d+)\.\s+(.*\S)\s*$/u, line) do
      [_, num, label] -> %{key: num, label: label}
      nil -> nil
    end
  end

  # Nearest non-blank line above the options block, preferring one with "?".
  defp question_above(lines, option_count) do
    above =
      lines
      |> Enum.reverse()
      |> Enum.drop_while(&(numbered_option(&1) != nil or blank?(&1)))
      |> Enum.take(6)
      |> Enum.reject(&blank?/1)

    _ = option_count

    Enum.find(above, &String.ends_with?(&1, "?")) || List.first(above)
  end

  # ── y/n prompt ───────────────────────────────────────────────────────────────

  defp detect_yes_no(lines) do
    last = lines |> Enum.reject(&blank?/1) |> List.last() || ""

    if Regex.match?(~r/[\(\[]y\/n[\)\]]\s*:?\s*$/i, last) do
      %{
        question: last,
        options: [%{key: "y", label: "Yes"}, %{key: "n", label: "No"}]
      }
    else
      nil
    end
  end

  # ── Helpers ──────────────────────────────────────────────────────────────────

  # Strip tmux/TUI box-drawing borders so menus inside boxes still match.
  defp strip_box(line) do
    line
    |> String.replace(~r/^[\s│|]+/u, "")
    |> String.replace(~r/[\s│|]+$/u, "")
  end

  defp drop_trailing_blanks(lines) do
    lines |> Enum.reverse() |> Enum.drop_while(&blank?/1) |> Enum.reverse()
  end

  # Blank, or a pure box-border line like "╰────╯".
  defp blank?(line), do: Regex.match?(~r/^[─━═╭╮╰╯└┘┌┐┤├\-\s]*$/u, line)
end
