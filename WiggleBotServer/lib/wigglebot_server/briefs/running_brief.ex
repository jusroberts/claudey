defmodule WigglebotServer.Briefs.RunningBrief do
  alias WigglebotServer.Briefs.Forecast
  alias WigglebotServer.LlamaClient

  # Running window: 6am–10am
  @run_start 6
  @run_end 10

  @moods [
    "enthusiastic coach",
    "zen buddhist monk",
    "grumpy drill sergeant",
    "overly competitive athlete",
    "sleepy but trying",
    "motivational poster",
    "very british and understated",
    "1990s infomercial host",
    "disappointed parent",
    "golden retriever energy",
  ]

  def generate(lat, lon) do
    with {:ok, hourly} <- Forecast.fetch_hourly(lat, lon) do
      w = hourly |> Forecast.window(@run_start, @run_end) |> Forecast.summarise()
      verdict = assess(w)
      {title, body} = format(w, verdict)
      {:ok, %{title: title, body: body, good: verdict in [:great, :good_layer]}}
    end
  end

  defp assess(nil), do: :unknown

  defp assess(w) do
    cond do
      w.temp < -20 or w.temp > 35 -> :bad
      Forecast.heavy_precip?(w.code) -> :bad
      Forecast.rain?(w.code) and w.precip_prob > 40 -> :bad
      w.temp < 5 -> :good_layer
      true -> :great
    end
  end

  defp format(nil, _), do: {"Running weather", "Couldn't fetch forecast."}

  defp format(w, verdict) do
    cond_str = "#{w.temp}°C feels like #{w.feels_like}°C, #{Forecast.desc(w.code)}, wind #{w.wind} km/h"

    case verdict do
      :great ->
        {llm_title(:great), "6–10am: #{cond_str}."}

      :good_layer ->
        {llm_title(:good_layer), "6–10am: #{cond_str}. Cold but doable."}

      :bad ->
        {"⛔ Skip the run today", "6–10am: #{cond_str}."}

      :unknown ->
        {"Running weather unavailable", "Couldn't fetch forecast."}
    end
  end

  defp llm_title(verdict) do
    mood = Enum.random(@moods)

    situation =
      case verdict do
        :great      -> "the weather looks great for a morning run"
        :good_layer -> "it's cold but still fine for a run if you layer up"
      end

    prompt = """
    You are sending a push notification to someone about to go for a morning run. \
    #{String.capitalize(situation)}. \
    Write a single short sentence (max 8 words) encouraging them to go, \
    in the tone of a #{mood}. \
    No quotes, no punctuation at the end, no hashtags, no emoji.
    """

    case LlamaClient.chat([%{role: "user", content: prompt}]) do
      {:ok, body} ->
        title =
          body
          |> get_in(["choices", Access.at(0), "message", "content"])
          |> then(&(if is_binary(&1), do: String.trim(&1), else: nil))

        if title && String.length(title) > 0 and String.length(title) < 80 do
          "🏃 #{title}"
        else
          default_title(verdict)
        end

      _ ->
        default_title(verdict)
    end
  end

  defp default_title(:great),      do: "🏃 Great day for a run"
  defp default_title(:good_layer), do: "🏃 Run day — layer up"
end
