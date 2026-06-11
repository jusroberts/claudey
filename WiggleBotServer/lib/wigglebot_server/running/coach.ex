defmodule WigglebotServer.Running.Coach do
  @moduledoc """
  Weekly training-plan generation.

  Builds a context from recent training load (distance, time, elevation,
  HR) and upcoming events, asks the LLM for a structured 7-day plan
  (strict JSON), stores it in coach_suggestions, and pushes a summary to
  the phone. Falls back to a simple heuristic plan when the LLM output
  can't be parsed, so the calendar always has something to show.
  """

  import Ecto.Query

  require Logger

  alias WigglebotServer.{LlamaClient, Push, Repo, Reports, Running}
  alias WigglebotServer.Running.CoachSuggestion

  @valid_types ~w(easy tempo intervals long hills recovery rest cross)

  @doc "Generates and stores the plan for the week starting next Monday."
  def plan_next_week do
    today = Date.utc_today()
    next_monday = Date.add(today, 8 - Date.day_of_week(today))
    plan_week(next_monday)
  end

  @doc "Generates and stores the plan for the week containing today."
  def replan_current_week do
    today = Date.utc_today()
    monday = Date.add(today, -(Date.day_of_week(today) - 1))
    plan_week(monday)
  end

  def plan_week(week_start) do
    suggestions =
      case llm_plan(week_start) do
        {:ok, days} -> days
        {:error, reason} ->
          Logger.warning("Coach: LLM plan failed (#{reason}), using fallback plan")
          fallback_plan(week_start)
      end

    store_week(week_start, suggestions)
    notify(week_start, suggestions)
    {:ok, suggestions}
  end

  @doc "Suggestions + actual runs for the week starting `week_start` (Date)."
  def week_view(week_start) do
    week_end = Date.add(week_start, 6)

    suggestions =
      Repo.all(
        from s in CoachSuggestion,
          where: s.day >= ^week_start and s.day <= ^week_end,
          order_by: [asc: s.day]
      )

    runs = Running.runs_between(week_start, week_end)
    events = Running.upcoming_events()

    %{week_start: week_start, suggestions: suggestions, runs: runs, events: events}
  end

  @doc "Human-readable current+next week plan, for the agent tool."
  def describe_plan do
    today = Date.utc_today()
    monday = Date.add(today, -(Date.day_of_week(today) - 1))
    week_end = Date.add(monday, 13)

    suggestions =
      Repo.all(
        from s in CoachSuggestion,
          where: s.day >= ^monday and s.day <= ^week_end,
          order_by: [asc: s.day]
      )

    if suggestions == [] do
      "No coach plan generated yet. Say \"replan my week\" to generate one."
    else
      Enum.map_join(suggestions, "\n", fn s ->
        marker = if s.day == today, do: " ← today", else: ""
        "#{Calendar.strftime(s.day, "%a %b %-d")}: [#{s.type}] #{s.title}" <>
          if(s.detail, do: " — #{s.detail}", else: "") <> marker
      end)
    end
  end

  # ── LLM plan ─────────────────────────────────────────────────────────────────

  defp llm_plan(week_start) do
    prompt = build_prompt(week_start)

    with {:ok, response} <- LlamaClient.chat([%{role: "user", content: prompt}]),
         content when is_binary(content) <-
           get_in(response, ["choices", Access.at(0), "message", "content"]),
         {:ok, days} <- parse_plan(content, week_start) do
      {:ok, days}
    else
      {:error, reason} -> {:error, reason}
      other -> {:error, "unexpected LLM response: #{inspect(other)}"}
    end
  end

  defp build_prompt(week_start) do
    load = Running.weekly_load(8)

    load_lines =
      Enum.map_join(load, "\n", fn w ->
        "- week of #{w.week_start}: #{w.runs} runs, #{Float.round(w.distance_km, 1)} km, " <>
          "#{w.duration_min} min, #{round(w.elevation_m)} m elevation" <>
          if(w.avg_hr, do: ", avg HR #{w.avg_hr}", else: "")
      end)

    events_lines =
      case Running.upcoming_events() do
        [] ->
          "none"

        events ->
          Enum.map_join(events, "\n", fn e ->
            days_out = Date.diff(e.date, week_start)

            "- #{e.name} on #{e.date} (#{days_out} days after week start)" <>
              if(e.distance_m, do: ", #{Float.round(e.distance_m / 1000, 1)} km", else: "") <>
              if(e.goal, do: ", goal: #{e.goal}", else: "")
          end)
      end

    """
    You are a running coach. Create a 7-day training plan for the week starting #{week_start} (a Monday).

    Recent training load (oldest week first):
    #{load_lines}

    Upcoming races/events:
    #{events_lines}

    Rules:
    - Respect the athlete's current volume; increase weekly distance by at most 10%.
    - Hard days (tempo/intervals/hills) must be followed by easy or rest days.
    - Include at least 2 rest days unless volume history clearly supports more running.
    - If a race falls in or shortly after this week, taper appropriately.

    Respond with ONLY a JSON array of exactly 7 objects, Monday first, no other text:
    [{"day": 1, "type": "easy|tempo|intervals|long|hills|recovery|rest|cross",
      "title": "short title", "detail": "distance/pace/effort guidance",
      "rationale": "one sentence why"}, ...]
    """
  end

  defp parse_plan(content, week_start) do
    # The model may wrap JSON in code fences or prose; extract the array.
    with %{"json" => raw} <- Regex.named_captures(~r/(?<json>\[.*\])/s, content),
         {:ok, days} when is_list(days) <- Jason.decode(raw),
         7 <- length(days) do
      parsed =
        days
        |> Enum.with_index()
        |> Enum.map(fn {d, idx} ->
          type = if d["type"] in @valid_types, do: d["type"], else: "easy"

          %{
            day: Date.add(week_start, idx),
            type: type,
            title: String.slice(d["title"] || String.capitalize(type), 0, 120),
            detail: d["detail"] && String.slice(d["detail"], 0, 300),
            rationale: d["rationale"] && String.slice(d["rationale"], 0, 300)
          }
        end)

      {:ok, parsed}
    else
      _ -> {:error, "could not parse plan JSON"}
    end
  end

  # Conservative default when the LLM is down: alternate easy runs and rest,
  # long run on Saturday, scaled off the recent average.
  defp fallback_plan(week_start) do
    load = Running.weekly_load(4)
    avg_km = (load |> Enum.map(& &1.distance_km) |> Enum.sum()) / max(length(load), 1)
    easy_km = max(Float.round(avg_km / 4, 1), 3.0)
    long_km = max(Float.round(avg_km / 2.5, 1), 5.0)

    [
      {0, "easy", "Easy run", "#{easy_km} km at conversational pace"},
      {1, "rest", "Rest", nil},
      {2, "easy", "Easy run", "#{easy_km} km at conversational pace"},
      {3, "rest", "Rest", nil},
      {4, "easy", "Easy run", "#{easy_km} km relaxed"},
      {5, "long", "Long run", "#{long_km} km, slow and steady"},
      {6, "rest", "Rest", nil}
    ]
    |> Enum.map(fn {offset, type, title, detail} ->
      %{
        day: Date.add(week_start, offset),
        type: type,
        title: title,
        detail: detail,
        rationale: "Fallback plan (LLM unavailable) based on recent volume"
      }
    end)
  end

  # ── Persistence + delivery ───────────────────────────────────────────────────

  defp store_week(week_start, suggestions) do
    week_end = Date.add(week_start, 6)

    Repo.delete_all(
      from s in CoachSuggestion, where: s.day >= ^week_start and s.day <= ^week_end
    )

    Enum.each(suggestions, fn s ->
      %CoachSuggestion{}
      |> CoachSuggestion.changeset(Map.put(s, :week_start, week_start))
      |> Repo.insert!()
    end)
  end

  defp notify(week_start, suggestions) do
    summary =
      Enum.map_join(suggestions, "\n", fn s ->
        "#{Calendar.strftime(s.day, "%a")}: #{s.title}"
      end)

    title = "Training plan for week of #{Calendar.strftime(week_start, "%b %-d")}"
    Reports.log("coach_plan", title, summary)
    Push.notify(title, summary, %{"channel" => "wigglebot_running"})
  end
end

defmodule WigglebotServer.Running.CoachSuggestion do
  use Ecto.Schema

  import Ecto.Changeset

  schema "coach_suggestions" do
    field :week_start, :date
    field :day, :date
    field :type, :string
    field :title, :string
    field :detail, :string
    field :rationale, :string

    timestamps(type: :utc_datetime)
  end

  def changeset(suggestion, attrs) do
    suggestion
    |> cast(attrs, [:week_start, :day, :type, :title, :detail, :rationale])
    |> validate_required([:week_start, :day, :type, :title])
    |> unique_constraint([:day])
  end
end
