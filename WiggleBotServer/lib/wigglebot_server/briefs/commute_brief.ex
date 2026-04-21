defmodule WigglebotServer.Briefs.CommuteBrief do
  alias WigglebotServer.Briefs.Forecast
  alias WigglebotServer.GtfsRt.ServiceAlerts
  require Logger

  # Morning walk to Milton GO: 7–9am
  @morning_start 7
  @morning_end 9
  # Afternoon: target 3:40pm train, so 14–17 window
  @afternoon_start 14
  @afternoon_end 17

  # Metrolinx GTFS-RT service alerts for the Milton GO line.
  # An empty feed (no alerts) is just a tiny protobuf header (~10 bytes).
  # Any real service alert adds substantial content, so size is a reliable proxy
  # until we add full protobuf parsing.
  @go_alerts_url "https://www.metrolinx.com/googletransit/googleFeed/MiltonLine/GTFS-RT/ServiceAlerts/ServiceAlerts.pb"

  def generate(lat, lon, is_run_day) do
    with {:ok, hourly} <- Forecast.fetch_hourly(lat, lon) do
      morning = hourly |> Forecast.window(@morning_start, @morning_end) |> Forecast.summarise()
      afternoon = hourly |> Forecast.window(@afternoon_start, @afternoon_end) |> Forecast.summarise()
      go = fetch_go_alerts()

      {title, body} = format(morning, afternoon, go, is_run_day)
      {:ok, %{title: title, body: body, go_alerts: go != :clear}}
    end
  end

  defp fetch_go_alerts do
    case Req.get(@go_alerts_url, receive_timeout: 6_000, raw: true) do
      {:ok, %{status: 200, body: body}} when is_binary(body) ->
        case ServiceAlerts.parse(body) do
          {:ok, []}     -> :clear
          {:ok, texts}  -> {:alerts, texts}
          {:error, reason} ->
            Logger.warning("GTFS-RT parse error: #{reason}")
            :unknown
        end

      _ ->
        Logger.warning("Could not fetch GO Milton line alerts")
        :unknown
    end
  end

  defp format(morning, afternoon, go, is_run_day) do
    m = window_str(morning, "Morning walk")
    a = window_str(afternoon, "3:40pm")
    go_str = go_str(go)
    run_note = if is_run_day, do: " Good conditions for a run too.", else: ""

    body = [m, a, go_str] |> Enum.reject(&(&1 == "")) |> Enum.join(" ") |> Kernel.<>(run_note)
    title = title(morning, go)
    {title, body}
  end

  defp window_str(nil, label), do: "#{label}: N/A."

  defp window_str(w, label) do
    umbrella = if Forecast.rain?(w.code) and w.precip_prob > 30, do: " ☂️", else: ""
    "#{label}: #{w.temp}°C, #{Forecast.desc(w.code)}#{umbrella}."
  end

  defp go_str(:clear), do: ""
  defp go_str({:alerts, texts}), do: "⚠️ GO alert: #{Enum.join(texts, " / ")}"
  defp go_str(:unknown), do: ""

  defp title(nil, _), do: "🚆 Commute today"

  defp title(w, go) do
    alert_prefix = if match?({:alerts, _}, go), do: "⚠️ ", else: ""

    weather_label =
      cond do
        Forecast.heavy_precip?(w.code) or (Forecast.rain?(w.code) and w.precip_prob > 50) ->
          "rain today"

        w.temp < 0 ->
          "freezing — dress warm"

        w.temp < 8 ->
          "cold this morning"

        true ->
          "good conditions"
      end

    "#{alert_prefix}🚆 Commute — #{weather_label}"
  end
end
