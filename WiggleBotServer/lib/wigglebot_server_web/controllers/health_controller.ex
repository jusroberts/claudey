defmodule WigglebotServerWeb.HealthController do
  use WigglebotServerWeb, :controller

  def index(conn, _params) do
    json(conn, %{status: "ok"})
  end
end
