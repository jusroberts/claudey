defmodule WigglebotServerWeb.TmuxChannel do
  use Phoenix.Channel

  require Logger

  @poll_interval 1_000

  @impl true
  def join("tmux:" <> session_name, _params, socket) do
    Logger.info("TmuxChannel join: #{session_name}")
    socket = assign(socket, :session_name, session_name)
    socket = assign(socket, :last_hash, nil)

    case capture_pane(session_name) do
      {:ok, content} ->
        send(self(), :poll)
        {:ok, %{snapshot: content}, assign(socket, :last_hash, hash(content))}

      {:error, reason} ->
        {:error, %{reason: reason}}
    end
  end

  @impl true
  def handle_in("send_input", %{"text" => text}, socket) do
    session = socket.assigns.session_name
    System.cmd("tmux", ["send-keys", "-t", session, text])
    {:noreply, socket}
  end

  @impl true
  def handle_info(:poll, socket) do
    session = socket.assigns.session_name

    socket =
      case capture_pane(session) do
        {:ok, content} ->
          h = hash(content)

          if h != socket.assigns.last_hash do
            push(socket, "output", %{content: content})
            assign(socket, :last_hash, h)
          else
            socket
          end

        {:error, _} ->
          push(socket, "session_ended", %{})
          socket
      end

    Process.send_after(self(), :poll, @poll_interval)
    {:noreply, socket}
  end

  defp capture_pane(session_name) do
    case System.cmd("tmux", ["capture-pane", "-t", session_name, "-p", "-S", "-"],
           stderr_to_stdout: true
         ) do
      {output, 0} -> {:ok, output}
      {msg, _}    -> {:error, String.trim(msg)}
    end
  end

  defp hash(content), do: :erlang.phash2(content)
end
