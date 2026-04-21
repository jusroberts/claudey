defmodule WigglebotServer.AgentSession do
  @moduledoc """
  GenServer managing one client's conversation history and agentic loop.

  One session per client_id, started on first connect, kept alive across
  reconnects, reaped after @idle_timeout_ms of inactivity.
  """

  use GenServer, restart: :temporary

  alias WigglebotServer.{LlamaClient, Tools.ServerTools}
  alias WigglebotServer.Tools.Registry, as: ToolRegistry

  require Logger

  @max_tool_rounds 6
  @idle_timeout_ms :timer.minutes(30)

  # ── Public API ───────────────────────────────────────────────────────────────

  def start_link(client_id) do
    GenServer.start_link(__MODULE__, client_id, name: via(client_id))
  end

  def ensure_started(client_id) do
    case DynamicSupervisor.start_child(
           WigglebotServer.SessionSupervisor,
           {__MODULE__, client_id}
         ) do
      {:ok, _} -> :ok
      {:error, {:already_started, _}} -> :ok
      err -> err
    end
  end

  def send_message(client_id, text, channel_pid, location \\ nil) do
    GenServer.cast(via(client_id), {:send_message, text, channel_pid, location})
  end

  def tool_result(client_id, tool_call_id, result) do
    GenServer.cast(via(client_id), {:tool_result, tool_call_id, result})
  end

  def clear_history(client_id) do
    GenServer.cast(via(client_id), :clear_history)
  end

  # ── GenServer callbacks ───────────────────────────────────────────────────────

  @impl true
  def init(client_id) do
    Logger.info("AgentSession started for #{client_id}")

    state = %{
      client_id: client_id,
      history: [system_message()],
      pending_tool_call: nil,
      channel_pid: nil,
      location: nil
    }

    {:ok, state, @idle_timeout_ms}
  end

  @impl true
  def handle_cast({:send_message, text, channel_pid, location}, state) do
    state = %{state | channel_pid: channel_pid, location: location || state.location}
    push(state, "thinking", %{})

    history = state.history ++ [%{role: "user", content: text}]
    state = %{state | history: history}

    Task.start(fn -> run_loop(state, channel_pid) end)

    {:noreply, state, @idle_timeout_ms}
  end

  @impl true
  def handle_cast({:tool_result, tool_call_id, result}, state) do
    case state.pending_tool_call do
      {^tool_call_id, from_pid} ->
        send(from_pid, {:tool_result, tool_call_id, result})
        {:noreply, %{state | pending_tool_call: nil}, @idle_timeout_ms}

      _ ->
        Logger.warning("Unexpected tool_result for #{tool_call_id}")
        {:noreply, state, @idle_timeout_ms}
    end
  end

  @impl true
  def handle_cast({:register_pending, tool_call_id, from_pid}, state) do
    {:noreply, %{state | pending_tool_call: {tool_call_id, from_pid}}, @idle_timeout_ms}
  end

  @impl true
  def handle_cast(:clear_history, state) do
    {:noreply, %{state | history: [system_message()]}, @idle_timeout_ms}
  end

  @impl true
  def handle_info(:timeout, state) do
    Logger.info("AgentSession #{state.client_id} idle timeout, stopping")
    {:stop, :normal, state}
  end

  # ── Agentic loop ──────────────────────────────────────────────────────────────

  defp run_loop(state, channel_pid, round \\ 0)

  defp run_loop(_state, channel_pid, round) when round >= @max_tool_rounds do
    push_to(channel_pid, "error", %{message: "Exceeded max tool rounds"})
  end

  defp run_loop(state, channel_pid, round) do
    case LlamaClient.chat(state.history, ToolRegistry.for_llama()) do
      {:ok, response} ->
        message = response["choices"] |> List.first() |> Map.get("message")
        history = state.history ++ [message]
        state = %{state | history: history}

        case message["tool_calls"] do
          nil ->
            push_to(channel_pid, "assistant_message", %{text: message["content"] || ""})

          tool_calls ->
            handle_tool_calls(state, channel_pid, tool_calls, round)
        end

      {:error, reason} ->
        push_to(channel_pid, "error", %{message: reason})
    end
  end

  defp handle_tool_calls(state, channel_pid, tool_calls, round) do
    {state, _tool_results} =
      Enum.reduce(tool_calls, {state, []}, fn call, {state, results} ->
        tool_call_id = call["id"] || "call_#{round}_#{length(results)}"
        name = call["function"]["name"]
        args = decode_args(call["function"]["arguments"])

        Logger.debug("Tool call: #{name} args=#{inspect(args)}")

        result =
          case ToolRegistry.side(name) do
            :server ->
              location_fn = fn ->
                case state.location do
                  {lat, lon} -> {:ok, {lat, lon}}
                  _ -> {:error, "no location — ask the user to grant location permission"}
                end
              end
              case ServerTools.execute(name, args, location_fn) do
                {:ok, r} -> r
                {:error, r} -> r
              end

            :device ->
              push_to(channel_pid, "tool_request", %{
                tool_call_id: tool_call_id,
                name: name,
                args: args
              })
              await_tool_result(state.client_id, tool_call_id)

            :unknown ->
              "Unknown tool: #{name}"
          end

        tool_msg = %{
          role: "tool",
          content: result,
          tool_call_id: tool_call_id
        }

        {%{state | history: state.history ++ [tool_msg]}, results ++ [result]}
      end)

    run_loop(state, channel_pid, round + 1)
  end

  defp await_tool_result(client_id, tool_call_id) do
    GenServer.cast(via(client_id), {:register_pending, tool_call_id, self()})

    receive do
      {:tool_result, ^tool_call_id, result} -> result
    after
      30_000 -> "Tool #{tool_call_id} timed out"
    end
  end

  defp decode_args(args) when is_binary(args) do
    case Jason.decode(args) do
      {:ok, map} -> map
      _ -> %{}
    end
  end

  defp decode_args(args) when is_map(args), do: args
  defp decode_args(_), do: %{}

  defp push(state, event, payload) do
    if state.channel_pid, do: push_to(state.channel_pid, event, payload)
  end

  defp push_to(pid, event, payload) do
    send(pid, {:push, event, payload})
  end

  defp system_message do
    %{
      role: "system",
      content: Application.get_env(:wigglebot_server, :system_prompt, default_prompt())
    }
  end

  defp default_prompt do
    """
    You are a helpful phone assistant running on an Android device. \
    You can control apps, play music, open audiobooks, and perform device actions using the tools available to you.

    When a user asks you to do something, figure out which tool(s) to call. \
    If you're not sure whether an app is installed, call get_installed_apps first.

    After executing tools, give a short, friendly confirmation of what you did. \
    Don't over-explain. Be concise.
    """
  end

  defp via(client_id) do
    {:via, Registry, {WigglebotServer.SessionRegistry, client_id}}
  end
end
