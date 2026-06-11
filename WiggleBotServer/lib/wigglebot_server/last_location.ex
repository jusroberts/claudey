defmodule WigglebotServer.LastLocation do
  @moduledoc """
  Remembers the most recent device GPS fix (sent with chat messages) so
  server-initiated jobs like the morning digest can fetch local weather
  without waking the phone.
  """

  @key {__MODULE__, :location}

  def put(lat, lon) when is_number(lat) and is_number(lon) do
    :persistent_term.put(@key, {lat, lon, System.os_time(:second)})
  end

  def put(_, _), do: :ok

  @doc "Returns {:ok, {lat, lon}} when a fix newer than `max_age_s` exists."
  def get(max_age_s \\ 86_400 * 3) do
    case :persistent_term.get(@key, nil) do
      {lat, lon, at} ->
        if System.os_time(:second) - at <= max_age_s do
          {:ok, {lat, lon}}
        else
          :error
        end

      nil ->
        :error
    end
  end
end
