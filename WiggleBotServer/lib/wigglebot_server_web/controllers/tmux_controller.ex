defmodule WigglebotServerWeb.TmuxController do
  use WigglebotServerWeb, :controller

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
end
