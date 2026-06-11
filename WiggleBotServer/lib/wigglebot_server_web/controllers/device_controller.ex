defmodule WigglebotServerWeb.DeviceController do
  use WigglebotServerWeb, :controller

  alias WigglebotServer.{Devices, Push}

  def register(conn, %{"token" => token} = params) do
    platform = Map.get(params, "platform", "android")

    case Devices.register(token, platform) do
      {:ok, _device} ->
        json(conn, %{status: "registered"})

      {:error, changeset} ->
        conn
        |> put_status(422)
        |> json(%{error: inspect(changeset.errors)})
    end
  end

  def register(conn, _params) do
    conn |> put_status(400) |> json(%{error: "token is required"})
  end

  def test_push(conn, _params) do
    if Push.configured?() do
      Push.notify("Test push", "Hello from the wigglebot server 👋", %{
        "channel" => "wigglebot_reminder"
      })

      json(conn, %{status: "sent", devices: length(Devices.all_tokens())})
    else
      conn |> put_status(503) |> json(%{error: "FCM not configured on server"})
    end
  end
end
