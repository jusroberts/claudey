defmodule WigglebotServer.Briefs.CommuteBrief do
  alias WigglebotServer.Briefs.Forecast
  alias WigglebotServer.Metrolinx.Client, as: MetrolinxClient
  require Logger

  # Morning walk to Milton GO: 7–9am
  @morning_start 7
  @morning_end 9
  # Afternoon: target 3:40pm train, so 14–17 window
  @afternoon_start 14
  @afternoon_end 17

  def generate(lat, lon, is_run_day, direction \\ "outbound") do
    with {:ok, hourly} <- Forecast.fetch_hourly(lat, lon) do
      morning = hourly |> Forecast.window(@morning_start, @morning_end) |> Forecast.summarise()
      afternoon = hourly |> Forecast.window(@afternoon_start, @afternoon_end) |> Forecast.summarise()
      go = fetch_go_alerts()

      {title, body} = format(morning, afternoon, go, is_run_day, direction)
      {:ok, %{title: title, body: body, go_alerts: go != :clear}}
    end
  end

  defp fetch_go_alerts do
    case MetrolinxClient.get_service_alerts() do
      {:ok, []}    -> :clear
      {:ok, texts} -> {:alerts, texts}
      {:error, reason} ->
        Logger.warning("Metrolinx alerts error: #{reason}")
        :unknown
    end
  end

  defp format(morning, afternoon, go, is_run_day, direction) do
    {weather_window, weather_label, train_time} =
      if direction == "inbound",
        do: {afternoon, "Afternoon", "3:40pm train home"},
        else: {morning, "Morning walk", "3:40pm"}

    w = window_str(weather_window, weather_label)
    a = if direction == "outbound", do: window_str(afternoon, train_time), else: ""
    go_str = go_str(go)
    run_note = if is_run_day and direction == "outbound", do: " Good conditions for a run too.", else: ""

    body = [w, a, go_str] |> Enum.reject(&(&1 == "")) |> Enum.join(" ") |> Kernel.<>(run_note)
    title = title(weather_window, go, direction)
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

  defp title(nil, _, _), do: "🚆 Commute today"

  defp title(w, go, direction) do
    alert_prefix = if match?({:alerts, _}, go), do: "⚠️ ", else: ""

    weather_label =
      cond do
        Forecast.heavy_precip?(w.code) or (Forecast.rain?(w.code) and w.precip_prob > 50) ->
          if direction == "inbound", do: "rain — head home", else: "rain today"

        w.temp < 0 ->
          "freezing — dress warm"

        w.temp < 8 ->
          if direction == "inbound", do: "cold — head home", else: "cold this morning"

        true ->
          if direction == "inbound", do: "good time to head home", else: "good conditions"
      end

    "#{alert_prefix}🚆 Commute — #{weather_label}"
  end
end
