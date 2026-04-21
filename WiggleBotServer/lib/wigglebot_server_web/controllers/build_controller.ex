defmodule WigglebotServerWeb.BuildController do
  use WigglebotServerWeb, :controller

  alias WigglebotServer.BuildManager

  def trigger(conn, _params) do
    case BuildManager.trigger() do
      :ok              -> json(conn, %{status: "building"})
      {:error, reason} -> conn |> put_status(409) |> json(%{error: reason})
    end
  end

  def status(conn, _params) do
    state = BuildManager.status()
    json(conn, %{
      status:  state.status,
      message: state.message,
      builds:  state.builds,
    })
  end

  def list(conn, _params) do
    json(conn, BuildManager.list_builds())
  end
end
