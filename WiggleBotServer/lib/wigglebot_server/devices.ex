defmodule WigglebotServer.Devices do
  @moduledoc "Registered push-notification targets (phones running the app)."

  import Ecto.Query

  alias WigglebotServer.Repo
  alias WigglebotServer.Devices.Device

  def register(token, platform \\ "android") when is_binary(token) do
    now = DateTime.utc_now() |> DateTime.truncate(:second)

    %Device{}
    |> Device.changeset(%{token: token, platform: platform, last_seen_at: now})
    |> Repo.insert(
      on_conflict: {:replace, [:platform, :last_seen_at, :updated_at]},
      conflict_target: :token
    )
  end

  def all_tokens do
    Repo.all(from d in Device, select: d.token)
  end

  def delete_token(token) do
    Repo.delete_all(from d in Device, where: d.token == ^token)
  end
end

defmodule WigglebotServer.Devices.Device do
  use Ecto.Schema

  import Ecto.Changeset

  schema "devices" do
    field :token, :string
    field :platform, :string, default: "android"
    field :last_seen_at, :utc_datetime

    timestamps(type: :utc_datetime)
  end

  def changeset(device, attrs) do
    device
    |> cast(attrs, [:token, :platform, :last_seen_at])
    |> validate_required([:token])
    |> unique_constraint(:token)
  end
end
