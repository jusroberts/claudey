defmodule WigglebotServer.Push do
  @moduledoc """
  Sends FCM HTTP v1 data messages to all registered devices.

  Data-only, high-priority messages are the only delivery path Android
  exempts from Doze without a foreground service. The app's FCM service
  decides whether to show a notification (`type: "notify"`) or wake a
  worker (`type: "wake"`).

  Requires `:fcm_service_account_path` to point at a Firebase service
  account JSON file. When unconfigured, every call is a logged no-op so
  the rest of the server keeps working.
  """

  require Logger

  alias WigglebotServer.Devices

  @doc "Show a notification on every registered device."
  def notify(title, body, extra \\ %{}) do
    extra
    |> Map.merge(%{"type" => "notify", "title" => title, "body" => body})
    |> broadcast()
  end

  @doc "Wake a client-side worker (e.g. \"run_reminder\") on every device."
  def wake(worker, extra \\ %{}) do
    extra
    |> Map.merge(%{"type" => "wake", "worker" => worker})
    |> broadcast()
  end

  def configured? do
    Application.get_env(:wigglebot_server, :fcm_service_account_path) != nil
  end

  defp broadcast(data) do
    if configured?() do
      data = Map.new(data, fn {k, v} -> {to_string(k), to_string(v)} end)

      tokens = Devices.all_tokens()

      if tokens == [] do
        Logger.warning("Push: no devices registered, dropping #{inspect(data["type"])}")
      end

      Enum.each(tokens, &send_message(&1, data))
      :ok
    else
      Logger.info("Push: FCM not configured, dropping message #{inspect(data)}")
      :ok
    end
  end

  defp send_message(token, data) do
    with {:ok, access_token} <- fetch_access_token(),
         {:ok, project_id} <- project_id() do
      body = %{
        message: %{
          token: token,
          data: data,
          android: %{priority: "HIGH"}
        }
      }

      url = "https://fcm.googleapis.com/v1/projects/#{project_id}/messages:send"

      case Req.post(url,
             json: body,
             auth: {:bearer, access_token},
             receive_timeout: 15_000
           ) do
        {:ok, %{status: 200}} ->
          :ok

        {:ok, %{status: status, body: resp}} when status in [404, 410] ->
          Logger.info("Push: token unregistered, removing device (#{inspect(resp)})")
          Devices.delete_token(token)

        {:ok, %{status: status, body: resp}} ->
          Logger.warning("Push: FCM returned #{status}: #{inspect(resp)}")

        {:error, reason} ->
          Logger.warning("Push: FCM request failed: #{inspect(reason)}")
      end
    else
      {:error, reason} ->
        Logger.warning("Push: could not prepare FCM request: #{inspect(reason)}")
    end
  end

  defp fetch_access_token do
    case Goth.fetch(WigglebotServer.Goth) do
      {:ok, %{token: token}} -> {:ok, token}
      {:error, reason} -> {:error, reason}
    end
  end

  defp project_id do
    case :persistent_term.get({__MODULE__, :project_id}, nil) do
      nil ->
        path = Application.get_env(:wigglebot_server, :fcm_service_account_path)

        with {:ok, raw} <- File.read(path),
             {:ok, %{"project_id" => id}} <- Jason.decode(raw) do
          :persistent_term.put({__MODULE__, :project_id}, id)
          {:ok, id}
        else
          err -> {:error, "cannot read project_id from #{path}: #{inspect(err)}"}
        end

      id ->
        {:ok, id}
    end
  end
end
