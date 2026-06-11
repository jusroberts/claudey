defmodule WigglebotServer.Repo.Migrations.CreateDevices do
  use Ecto.Migration

  def change do
    create table(:devices) do
      add :token, :string, null: false
      add :platform, :string, null: false, default: "android"
      add :last_seen_at, :utc_datetime

      timestamps(type: :utc_datetime)
    end

    create unique_index(:devices, [:token])
  end
end
