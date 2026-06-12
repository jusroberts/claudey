defmodule WigglebotServer.Repo.Migrations.CreateOauthTokens do
  use Ecto.Migration

  def change do
    create table(:oauth_tokens) do
      add :provider, :string, null: false
      add :access_token, :text, null: false
      add :refresh_token, :text
      add :expires_at, :utc_datetime

      timestamps(type: :utc_datetime)
    end

    create unique_index(:oauth_tokens, [:provider])
  end
end
