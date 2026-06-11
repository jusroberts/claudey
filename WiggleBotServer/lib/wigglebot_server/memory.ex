defmodule WigglebotServer.Memory do
  @moduledoc "Long-lived notes the assistant can remember and recall."

  import Ecto.Query

  alias WigglebotServer.Repo
  alias WigglebotServer.Memory.Note

  def remember(content) when is_binary(content) and content != "" do
    %Note{content: String.slice(content, 0, 2000)} |> Repo.insert()
  end

  def remember(_), do: {:error, :empty}

  @doc "Notes matching `query` (substring, case-insensitive); all recent when nil."
  def recall(query \\ nil, limit \\ 10) do
    base = from n in Note, order_by: [desc: n.inserted_at], limit: ^limit

    query_notes =
      case query do
        q when is_binary(q) and q != "" ->
          like = "%#{String.replace(q, ~r/[%_]/, "")}%"
          from n in base, where: like(n.content, ^like)

        _ ->
          base
      end

    Repo.all(query_notes)
  end

  def forget(id) do
    case Repo.get(Note, id) do
      nil -> {:error, :not_found}
      note -> Repo.delete(note)
    end
  end
end

defmodule WigglebotServer.Memory.Note do
  use Ecto.Schema

  schema "notes" do
    field :content, :string

    timestamps(type: :utc_datetime)
  end
end
