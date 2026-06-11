defmodule WigglebotServerWeb.TmuxChannel do
  use Phoenix.Channel

  alias WigglebotServer.Tmux.PromptDetector

  require Logger

  @poll_interval 1_000

  # Named keys the client may send via "send_key".
  @named_keys ~w(Enter Escape Tab BTab Up Down Left Right PageUp PageDown Home End Space C-c C-d C-u C-z)

  @impl true
  def join("tmux:" <> session_name, _params, socket) do
    Logger.info("TmuxChannel join: #{session_name}")
    socket = assign(socket, :session_name, session_name)
    socket = assign(socket, :last_hash, nil)
    socket = assign(socket, :last_prompt, nil)

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
    # -l sends the text literally; without it tmux would interpret key names.
    System.cmd("tmux", ["send-keys", "-t", session, "-l", text])
    {:noreply, socket}
  end

  @impl true
  def handle_in("send_key", %{"key" => key}, socket) when key in @named_keys do
    session = socket.assigns.session_name
    System.cmd("tmux", ["send-keys", "-t", session, key])
    {:noreply, socket}
  end

  @impl true
  def handle_in("send_key", %{"key" => key}, socket) do
    Logger.warning("TmuxChannel: rejected unknown key #{inspect(key)}")
    {:noreply, socket}
  end

  @impl true
  def handle_in("answer_prompt", %{"key" => key}, socket) do
    # One-tap prompt answer: digits/letters are typed then submitted with
    # Enter; named keys (e.g. plain Enter) are sent as-is.
    session = socket.assigns.session_name

    cond do
      key in @named_keys ->
        System.cmd("tmux", ["send-keys", "-t", session, key])

      Regex.match?(~r/^[a-zA-Z0-9]$/, key) ->
        System.cmd("tmux", ["send-keys", "-t", session, "-l", key])
        System.cmd("tmux", ["send-keys", "-t", session, "Enter"])

      true ->
        Logger.warning("TmuxChannel: rejected prompt answer #{inspect(key)}")
    end

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

            socket
            |> assign(:last_hash, h)
            |> push_prompt_state(content)
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

  defp push_prompt_state(socket, content) do
    prompt = PromptDetector.detect(content)

    cond do
      prompt != nil and prompt != socket.assigns.last_prompt ->
        push(socket, "prompt_detected", prompt)
        assign(socket, :last_prompt, prompt)

      prompt == nil and socket.assigns.last_prompt != nil ->
        push(socket, "prompt_cleared", %{})
        assign(socket, :last_prompt, nil)

      true ->
        socket
    end
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
