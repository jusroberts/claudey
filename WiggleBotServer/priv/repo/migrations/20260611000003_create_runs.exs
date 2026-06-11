defmodule WigglebotServer.Repo.Migrations.CreateRuns do
  use Ecto.Migration

  def change do
    create table(:runs) do
      add :source, :string, null: false
      add :external_id, :string, null: false
      add :started_at, :utc_datetime, null: false
      add :duration_s, :integer
      add :distance_m, :float
      add :avg_hr, :integer
      add :max_hr, :integer
      add :elevation_gain_m, :float
      add :calories, :float
      add :title, :string

      timestamps(type: :utc_datetime)
    end

    create unique_index(:runs, [:source, :external_id])
    create index(:runs, [:started_at])

    create table(:events) do
      add :name, :string, null: false
      add :date, :date, null: false
      add :distance_m, :float
      add :goal, :string
      add :notes, :string

      timestamps(type: :utc_datetime)
    end

    create index(:events, [:date])

    create table(:coach_suggestions) do
      add :week_start, :date, null: false
      add :day, :date, null: false
      add :type, :string, null: false
      add :title, :string, null: false
      add :detail, :string
      add :rationale, :string

      timestamps(type: :utc_datetime)
    end

    create index(:coach_suggestions, [:week_start])
    create unique_index(:coach_suggestions, [:day])
  end
end
