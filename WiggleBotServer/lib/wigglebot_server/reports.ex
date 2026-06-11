defmodule WigglebotServer.Reports do
  @moduledoc "Log of generated reports (finance anomalies, coach plans, …)."

  import Ecto.Query

  alias WigglebotServer.Repo
  alias WigglebotServer.Reports.Report

  def log(kind, title, body) do
    %Report{kind: kind, title: title, body: body} |> Repo.insert()
  end

  def latest(kind, count \\ 1) do
    Repo.all(
      from r in Report,
        where: r.kind == ^kind,
        order_by: [desc: r.inserted_at],
        limit: ^count
    )
  end
end

defmodule WigglebotServer.Reports.Report do
  use Ecto.Schema

  schema "report_log" do
    field :kind, :string
    field :title, :string
    field :body, :string

    timestamps(type: :utc_datetime)
  end
end
