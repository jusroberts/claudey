defmodule WigglebotServerWeb.BriefController do
  use WigglebotServerWeb, :controller

  alias WigglebotServer.Briefs.{RunningBrief, CommuteBrief}

  def run(conn, params) do
    with {:ok, lat, lon} <- parse_coords(params) do
      case RunningBrief.generate(lat, lon) do
        {:ok, brief} -> json(conn, brief)
        {:error, reason} -> conn |> put_status(502) |> json(%{error: reason})
      end
    else
      :error -> conn |> put_status(400) |> json(%{error: "lat and lon required"})
    end
  end

  def commute(conn, params) do
    with {:ok, lat, lon} <- parse_coords(params) do
      is_run_day = params["is_run_day"] == "true"

      case CommuteBrief.generate(lat, lon, is_run_day) do
        {:ok, brief} -> json(conn, brief)
        {:error, reason} -> conn |> put_status(502) |> json(%{error: reason})
      end
    else
      :error -> conn |> put_status(400) |> json(%{error: "lat and lon required"})
    end
  end

  defp parse_coords(%{"lat" => lat_s, "lon" => lon_s}) do
    with {lat, _} <- Float.parse(lat_s),
         {lon, _} <- Float.parse(lon_s) do
      {:ok, lat, lon}
    else
      _ -> :error
    end
  end

  defp parse_coords(_), do: :error
end
