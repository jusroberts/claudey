defmodule WigglebotServer.GtfsRt.ServiceAlerts do
  @moduledoc """
  Parses a GTFS-RT ServiceAlerts feed binary and extracts alert header texts.

  Relevant field numbers (from the GTFS-RT proto spec):
    FeedMessage  : entity(2) -> FeedEntity
    FeedEntity   : alert(5)  -> Alert
    Alert        : header_text(10) -> TranslatedString
    TranslatedString : translation(1) -> Translation
    Translation  : text(1), language(2)
  """

  alias WigglebotServer.GtfsRt.Protobuf

  @doc """
  Returns {:ok, [alert_text]} where each entry is the English header text
  of one service alert, or {:ok, []} if the feed is empty.
  """
  @spec parse(binary()) :: {:ok, [String.t()]} | {:error, String.t()}
  def parse(binary) when is_binary(binary) do
    try do
      feed = Protobuf.decode(binary)

      texts =
        feed
        |> Protobuf.get_all(2)           # repeated FeedEntity
        |> Enum.flat_map(&entity_texts/1)
        |> Enum.uniq()

      {:ok, texts}
    rescue
      e -> {:error, Exception.message(e)}
    end
  end

  defp entity_texts(entity_bytes) when is_binary(entity_bytes) do
    entity = Protobuf.decode(entity_bytes)

    case Protobuf.get(entity, 5) do       # Alert
      nil -> []
      alert_bytes ->
        alert = Protobuf.decode(alert_bytes)
        header_text(alert)
    end
  end

  defp entity_texts(_), do: []

  defp header_text(alert) do
    case Protobuf.get(alert, 10) do       # header_text TranslatedString
      nil -> []
      ts_bytes ->
        ts = Protobuf.decode(ts_bytes)

        ts
        |> Protobuf.get_all(1)            # repeated Translation
        |> Enum.map(&translation_text/1)
        |> Enum.filter(&is_binary/1)
        |> preferred_language()
    end
  end

  defp translation_text(t_bytes) when is_binary(t_bytes) do
    t = Protobuf.decode(t_bytes)
    Protobuf.get(t, 1)                    # text field
  end

  defp translation_text(_), do: nil

  # Return the English translation if present, otherwise the first one.
  defp preferred_language([]), do: []
  defp preferred_language([text]), do: [text]
  defp preferred_language(texts), do: [List.first(texts)]
end
