defmodule WigglebotServerWeb.RunController do
  use WigglebotServerWeb, :controller

  alias WigglebotServer.Running
  alias WigglebotServer.Running.Coach

  @doc """
  Health Connect sync from the phone. Body: {"runs": [run, ...]} where run =
  {external_id, started_at (ISO8601), duration_s, distance_m, avg_hr,
   max_hr, elevation_gain_m, calories, title}
  """
  def sync(conn, %{"runs" => runs}) when is_list(runs) do
    results =
      Enum.map(runs, fn r ->
        with {:ok, started_at, _} <- DateTime.from_iso8601(r["started_at"] || "") do
          Running.upsert_run(%{
            source: "health_connect",
            external_id: to_string(r["external_id"]),
            started_at: DateTime.truncate(started_at, :second),
            duration_s: r["duration_s"],
            distance_m: r["distance_m"],
            avg_hr: r["avg_hr"],
            max_hr: r["max_hr"],
            elevation_gain_m: r["elevation_gain_m"],
            calories: r["calories"],
            title: r["title"]
          })
        else
          _ -> {:error, :bad_timestamp}
        end
      end)

    json(conn, %{
      received: length(runs),
      inserted: Enum.count(results, &(&1 == {:ok, :inserted})),
      duplicates: Enum.count(results, &(&1 == {:ok, :duplicate})),
      errors: Enum.count(results, &match?({:error, _}, &1))
    })
  end

  def sync(conn, _params) do
    conn |> put_status(400) |> json(%{error: "body must be {\"runs\": [...]}"})
  end

  def coach_week(conn, params) do
    week_start =
      case Date.from_iso8601(Map.get(params, "start", "")) do
        {:ok, date} -> Date.add(date, -(Date.day_of_week(date) - 1))
        _ ->
          today = Date.utc_today()
          Date.add(today, -(Date.day_of_week(today) - 1))
      end

    view = Coach.week_view(week_start)

    json(conn, %{
      week_start: Date.to_iso8601(view.week_start),
      suggestions:
        Enum.map(view.suggestions, fn s ->
          %{
            day: Date.to_iso8601(s.day),
            type: s.type,
            title: s.title,
            detail: s.detail,
            rationale: s.rationale
          }
        end),
      runs:
        Enum.map(view.runs, fn r ->
          %{
            started_at: DateTime.to_iso8601(r.started_at),
            duration_s: r.duration_s,
            distance_m: r.distance_m,
            avg_hr: r.avg_hr,
            max_hr: r.max_hr,
            elevation_gain_m: r.elevation_gain_m,
            source: r.source,
            title: r.title
          }
        end),
      events:
        Enum.map(view.events, fn e ->
          %{
            id: e.id,
            name: e.name,
            date: Date.to_iso8601(e.date),
            distance_m: e.distance_m,
            goal: e.goal
          }
        end)
    })
  end

  def replan(conn, _params) do
    case Coach.replan_current_week() do
      {:ok, suggestions} -> json(conn, %{status: "ok", days: length(suggestions)})
    end
  end

  def create_event(conn, params) do
    attrs = %{
      name: params["name"],
      date: parse_date(params["date"]),
      distance_m: params["distance_m"],
      goal: params["goal"],
      notes: params["notes"]
    }

    case Running.add_event(attrs) do
      {:ok, event} ->
        json(conn, %{id: event.id, name: event.name, date: Date.to_iso8601(event.date)})

      {:error, changeset} ->
        conn |> put_status(422) |> json(%{error: inspect(changeset.errors)})
    end
  end

  def delete_event(conn, %{"id" => id}) do
    case Integer.parse(id) do
      {int_id, ""} ->
        case Running.delete_event(int_id) do
          {:ok, _} -> json(conn, %{status: "deleted"})
          {:error, :not_found} -> conn |> put_status(404) |> json(%{error: "not found"})
        end

      _ ->
        conn |> put_status(400) |> json(%{error: "bad id"})
    end
  end

  defp parse_date(s) when is_binary(s) do
    case Date.from_iso8601(s) do
      {:ok, d} -> d
      _ -> nil
    end
  end

  defp parse_date(_), do: nil
end
