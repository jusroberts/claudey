defmodule WigglebotServer.Running do
  @moduledoc """
  Run history (synced from Health Connect and/or Garmin) and race events.
  Cross-source dedupe: a run whose start time is within 10 minutes of an
  existing run from another source is treated as the same activity.
  """

  import Ecto.Query

  alias WigglebotServer.Repo
  alias WigglebotServer.Running.{Event, Run}

  @dedupe_window_s 600

  # ── Runs ─────────────────────────────────────────────────────────────────────

  @doc """
  Upserts a run. `attrs` must include source, external_id and started_at
  (DateTime). Returns {:ok, :inserted | :updated | :duplicate}.
  """
  def upsert_run(attrs) do
    case Repo.get_by(Run, source: attrs[:source], external_id: attrs[:external_id]) do
      %Run{} = existing ->
        existing |> Run.changeset(attrs) |> Repo.update()
        {:ok, :updated}

      nil ->
        if cross_source_duplicate?(attrs) do
          {:ok, :duplicate}
        else
          case %Run{} |> Run.changeset(attrs) |> Repo.insert() do
            {:ok, _} -> {:ok, :inserted}
            {:error, changeset} -> {:error, changeset}
          end
        end
    end
  end

  defp cross_source_duplicate?(attrs) do
    started_at = attrs[:started_at]
    from_t = DateTime.add(started_at, -@dedupe_window_s)
    to_t = DateTime.add(started_at, @dedupe_window_s)

    Repo.exists?(
      from r in Run,
        where:
          r.source != ^attrs[:source] and
            r.started_at >= ^from_t and
            r.started_at <= ^to_t
    )
  end

  def runs_between(from_date, to_date) do
    {:ok, from_dt, _} = DateTime.from_iso8601("#{from_date}T00:00:00Z")
    {:ok, to_dt, _} = DateTime.from_iso8601("#{to_date}T23:59:59Z")

    Repo.all(
      from r in Run,
        where: r.started_at >= ^from_dt and r.started_at <= ^to_dt,
        order_by: [asc: r.started_at]
    )
  end

  def ran_on?(date) do
    runs_between(date, date) != []
  end

  @doc """
  Cron entry for the 18:00 reminder: skips the FCM wake entirely when a run
  is already synced for today, otherwise wakes the phone's reminder worker
  (which re-checks Health Connect locally).
  """
  def wake_run_reminder do
    if ran_on?(Date.utc_today()) do
      require Logger
      Logger.info("Run reminder wake skipped — already ran today")
      :ok
    else
      WigglebotServer.Push.wake("run_reminder")
    end
  end

  @doc """
  Weekly training load for the `weeks` weeks ending today: list of maps
  (oldest first) with week_start, runs, distance_km, duration_min,
  elevation_m, avg_hr.
  """
  def weekly_load(weeks \\ 8) do
    today = Date.utc_today()
    this_week_start = Date.add(today, -(Date.day_of_week(today) - 1))

    for offset <- (weeks - 1)..0//-1 do
      week_start = Date.add(this_week_start, -offset * 7)
      week_end = Date.add(week_start, 6)
      runs = runs_between(week_start, week_end)

      hrs = runs |> Enum.map(& &1.avg_hr) |> Enum.reject(&is_nil/1)

      %{
        week_start: week_start,
        runs: length(runs),
        distance_km: (runs |> Enum.map(&(&1.distance_m || 0.0)) |> Enum.sum()) / 1000,
        duration_min: div(runs |> Enum.map(&(&1.duration_s || 0)) |> Enum.sum(), 60),
        elevation_m: runs |> Enum.map(&(&1.elevation_gain_m || 0.0)) |> Enum.sum(),
        avg_hr: if(hrs == [], do: nil, else: div(Enum.sum(hrs), length(hrs)))
      }
    end
  end

  # ── Events ───────────────────────────────────────────────────────────────────

  def add_event(attrs) do
    %Event{} |> Event.changeset(attrs) |> Repo.insert()
  end

  def upcoming_events do
    today = Date.utc_today()

    Repo.all(from e in Event, where: e.date >= ^today, order_by: [asc: e.date])
  end

  def delete_event(id) do
    case Repo.get(Event, id) do
      nil -> {:error, :not_found}
      event -> Repo.delete(event)
    end
  end
end

defmodule WigglebotServer.Running.Run do
  use Ecto.Schema

  import Ecto.Changeset

  schema "runs" do
    field :source, :string
    field :external_id, :string
    field :started_at, :utc_datetime
    field :duration_s, :integer
    field :distance_m, :float
    field :avg_hr, :integer
    field :max_hr, :integer
    field :elevation_gain_m, :float
    field :calories, :float
    field :title, :string

    timestamps(type: :utc_datetime)
  end

  def changeset(run, attrs) do
    run
    |> cast(attrs, [
      :source,
      :external_id,
      :started_at,
      :duration_s,
      :distance_m,
      :avg_hr,
      :max_hr,
      :elevation_gain_m,
      :calories,
      :title
    ])
    |> validate_required([:source, :external_id, :started_at])
    |> unique_constraint([:source, :external_id])
  end
end

defmodule WigglebotServer.Running.Event do
  use Ecto.Schema

  import Ecto.Changeset

  schema "events" do
    field :name, :string
    field :date, :date
    field :distance_m, :float
    field :goal, :string
    field :notes, :string

    timestamps(type: :utc_datetime)
  end

  def changeset(event, attrs) do
    event
    |> cast(attrs, [:name, :date, :distance_m, :goal, :notes])
    |> validate_required([:name, :date])
  end
end
