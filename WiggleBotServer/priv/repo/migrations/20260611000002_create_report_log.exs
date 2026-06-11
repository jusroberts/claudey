defmodule WigglebotServer.Repo.Migrations.CreateReportLog do
  use Ecto.Migration

  def change do
    create table(:report_log) do
      add :kind, :string, null: false
      add :title, :string, null: false
      add :body, :text, null: false

      timestamps(type: :utc_datetime)
    end

    create index(:report_log, [:kind, :inserted_at])
  end
end
