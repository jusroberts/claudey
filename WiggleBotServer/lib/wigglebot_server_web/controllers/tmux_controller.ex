defmodule WigglebotServerWeb.TmuxController do
  use WigglebotServerWeb, :controller

  @session_name_re ~r/^[a-zA-Z0-9_-]+$/

  def sessions(conn, _params) do
    sessions =
      case System.cmd("tmux", ["list-sessions", "-F", ~S(#{session_name}|#{session_created})],
             stderr_to_stdout: true
           ) do
        {output, 0} ->
          output
          |> String.trim()
          |> String.split("\n", trim: true)
          |> Enum.map(fn line ->
            case String.split(line, "|") do
              [name, created] -> %{name: name, created_at: String.to_integer(created)}
              [name]          -> %{name: name, created_at: 0}
            end
          end)

        _ ->
          []
      end

    json(conn, sessions)
  end

  @doc """
  Creates a detached tmux session. Body:
    name       — session name ([a-zA-Z0-9_-]+)
    cwd        — working directory, must resolve inside $HOME (default: $HOME)
    run_claude — when true, types `claude` + Enter into the new session
  """
  def create(conn, params) do
    name = Map.get(params, "name", "")
    run_claude = Map.get(params, "run_claude", false) in [true, "true"]

    with :ok <- validate_name(name),
         {:ok, cwd} <- WigglebotServer.HomeFs.resolve_dir(Map.get(params, "cwd")),
         :ok <- ensure_no_session(name),
         {_, 0} <-
           System.cmd("tmux", ["new-session", "-d", "-s", name, "-c", cwd],
             stderr_to_stdout: true
           ) do
      if run_claude do
        System.cmd("tmux", ["send-keys", "-t", name, "-l", "claude"])
        System.cmd("tmux", ["send-keys", "-t", name, "Enter"])
      end

      json(conn, %{name: name, cwd: cwd, claude: run_claude})
    else
      {:error, reason} ->
        conn |> put_status(400) |> json(%{error: reason})

      {output, _nonzero} when is_binary(output) ->
        conn |> put_status(500) |> json(%{error: "tmux: #{String.trim(output)}"})
    end
  end

  def dirs(conn, params) do
    case WigglebotServer.HomeFs.list_dirs(Map.get(params, "path")) do
      {:ok, listing} -> json(conn, listing)
      {:error, reason} -> conn |> put_status(400) |> json(%{error: reason})
    end
  end

  defp validate_name(name) do
    if Regex.match?(@session_name_re, name) do
      :ok
    else
      {:error, "session name must match [a-zA-Z0-9_-]+"}
    end
  end

  defp ensure_no_session(name) do
    case System.cmd("tmux", ["has-session", "-t", "=" <> name], stderr_to_stdout: true) do
      {_, 0} -> {:error, "session \"#{name}\" already exists"}
      _ -> :ok
    end
  end
end
