defmodule WigglebotServer.LlamaClient do
  @moduledoc "HTTP wrapper for llama.cpp's OpenAI-compatible /v1/chat/completions endpoint."

  @timeout_ms 120_000

  def chat(messages, tools \\ []) do
    body =
      %{messages: messages, stream: false}
      |> maybe_put(:tools, tools)

    case Req.post(base_url() <> "/v1/chat/completions",
           json: body,
           receive_timeout: @timeout_ms
         ) do
      {:ok, %{status: 200, body: body}} ->
        {:ok, body}

      {:ok, %{status: status, body: body}} ->
        {:error, "llama.cpp HTTP #{status}: #{inspect(body)}"}

      {:error, reason} ->
        {:error, "llama.cpp request failed: #{inspect(reason)}"}
    end
  end

  def ping do
    case Req.get(base_url() <> "/v1/models") do
      {:ok, %{status: 200, body: body}} ->
        models = Map.get(body, "data") || Map.get(body, "models") || []
        ids = Enum.map(models, &(&1["id"] || &1["model"] || &1["name"]))
        {:ok, ids}

      {:ok, %{status: status}} ->
        {:error, "ping failed: HTTP #{status}"}

      {:error, reason} ->
        {:error, inspect(reason)}
    end
  end

  defp base_url, do: Application.fetch_env!(:wigglebot_server, :llama_url)

  defp maybe_put(map, _key, []), do: map
  defp maybe_put(map, key, val), do: Map.put(map, key, val)
end
