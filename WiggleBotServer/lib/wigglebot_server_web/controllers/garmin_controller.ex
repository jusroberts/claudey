defmodule WigglebotServerWeb.GarminController do
  use WigglebotServerWeb, :controller

  alias WigglebotServer.Running.{GarminAuth, GarminClient}

  @doc "Starts the one-time Garmin consent flow (open in a browser)."
  def auth(conn, _params) do
    if GarminAuth.configured?() do
      redirect(conn, external: GarminAuth.authorize_url(callback_url(conn)))
    else
      conn
      |> put_status(503)
      |> json(%{error: "GARMIN_CLIENT_ID / GARMIN_CLIENT_SECRET not configured"})
    end
  end

  @doc "OAuth redirect target — exchanges the code and stores tokens."
  def callback(conn, %{"code" => code}) do
    case GarminAuth.exchange_code(code) do
      {:ok, _access_token} ->
        # Kick off a backfill of the last week right away.
        Task.start(fn -> GarminClient.sync_recent(24 * 7) end)

        conn
        |> put_resp_content_type("text/html")
        |> send_resp(200, """
        <html><body style="font-family: monospace; background: #0e0e11; color: #eee;">
        <h2>✓ Garmin connected</h2>
        <p>Tokens stored. Pulling the last week of activities now;
        the daily 05:30 sync takes it from here. You can close this tab.</p>
        </body></html>
        """)

      {:error, reason} ->
        conn |> put_status(502) |> json(%{error: reason})
    end
  end

  def callback(conn, params) do
    conn
    |> put_status(400)
    |> json(%{error: "missing code parameter", params: Map.keys(params)})
  end

  # Endpoint.url() reflects the public url config (Caddy/Tailscale host),
  # which must match the redirect URI registered with Garmin exactly.
  defp callback_url(_conn) do
    WigglebotServerWeb.Endpoint.url() <> "/api/garmin/callback"
  end
end
