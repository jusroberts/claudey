defmodule WigglebotServer.Digest do
  @moduledoc """
  Unified morning digest: one push assembling the running-conditions brief
  (when a recent GPS fix exists), today's coach suggestion, upcoming race
  countdowns, and any fresh finance flags — instead of N separate
  notifications.
  """

  import Ecto.Query

  require Logger

  alias WigglebotServer.{LastLocation, Push, Repo, Reports, Running}
  alias WigglebotServer.Briefs.RunningBrief
  alias WigglebotServer.Running.CoachSuggestion

  def send_morning_digest do
    sections =
      [weather_section(), coach_section(), events_section(), finance_section()]
      |> Enum.reject(&is_nil/1)

    if sections == [] do
      Logger.info("Digest: nothing to report, skipping push")
      :ok
    else
      body = Enum.join(sections, "\n\n")
      title = Calendar.strftime(Date.utc_today(), "Good morning — %A %b %-d")

      Reports.log("digest", title, body)
      Push.notify(title, body, %{"channel" => "wigglebot_running"})
      :ok
    end
  end

  defp weather_section do
    with {:ok, {lat, lon}} <- LastLocation.get(),
         {:ok, brief} <- RunningBrief.generate(lat, lon) do
      "🏃 #{brief.title}\n#{brief.body}"
    else
      _ -> nil
    end
  end

  defp coach_section do
    today = Date.utc_today()

    case Repo.one(from s in CoachSuggestion, where: s.day == ^today) do
      nil ->
        nil

      s ->
        "📋 Today's plan: [#{s.type}] #{s.title}" <>
          if(s.detail, do: "\n#{s.detail}", else: "")
    end
  end

  defp events_section do
    case Running.upcoming_events() do
      [] ->
        nil

      [next | _] ->
        days = Date.diff(next.date, Date.utc_today())

        if days <= 21 do
          "🏁 #{next.name} in #{days} day#{if days == 1, do: "", else: "s"}"
        else
          nil
        end
    end
  end

  # Include finance flags only when a report landed in the last day, so the
  # digest doesn't repeat week-old anomalies every morning.
  defp finance_section do
    case Reports.latest("finance_anomaly") do
      [report | _] ->
        age_s = DateTime.diff(DateTime.utc_now(), report.inserted_at)

        if age_s <= 86_400 and report.body != "No anomalies detected." do
          "💸 #{report.title}"
        else
          nil
        end

      [] ->
        nil
    end
  end
end
