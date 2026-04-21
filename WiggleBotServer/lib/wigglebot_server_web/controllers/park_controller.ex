defmodule WigglebotServerWeb.ParkController do
  use WigglebotServerWeb, :controller

  alias WigglebotServer.ParkBooking

  def book(conn, params) do
    with {:ok, inventory_id} <- integer_param(params, "inventory_id") do
      date = case params["date"] do
        nil -> Date.utc_today()
        d   -> Date.from_iso8601!(d)
      end

      case ParkBooking.book(inventory_id, date) do
        {:ok, reservation_id} ->
          json(conn, %{reservation: reservation_id})

        {:error, reason} ->
          conn |> put_status(502) |> json(%{error: reason})
      end
    else
      {:error, msg} ->
        conn |> put_status(400) |> json(%{error: msg})
    end
  end

  defp integer_param(params, key) do
    case params[key] do
      nil -> {:error, "missing #{key}"}
      v when is_integer(v) -> {:ok, v}
      v ->
        case Integer.parse(to_string(v)) do
          {n, ""} -> {:ok, n}
          _       -> {:error, "#{key} must be an integer"}
        end
    end
  end
end
