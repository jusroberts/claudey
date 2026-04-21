defmodule WigglebotServer.GtfsRt.Protobuf do
  import Bitwise

  @moduledoc """
  Minimal protobuf wire-format decoder.

  Decodes a binary into a list of {field_number, value} pairs.
  Wire types 0 (varint), 2 (length-delimited), 1 and 5 (fixed) are handled.
  Length-delimited values are returned as raw binaries — callers decode
  nested messages by calling decode/1 recursively.
  """

  @spec decode(binary()) :: [{non_neg_integer(), term()}]
  def decode(binary), do: decode(binary, [])

  defp decode(<<>>, acc), do: Enum.reverse(acc)

  defp decode(binary, acc) do
    {tag, rest} = varint(binary)
    field = tag >>> 3
    wire  = tag &&& 0x7

    {value, rest2} = field_value(wire, rest)
    decode(rest2, [{field, value} | acc])
  end

  # varint (wire type 0)
  defp field_value(0, binary) do
    varint(binary)
  end

  # 64-bit fixed (wire type 1)
  defp field_value(1, <<v::little-64, rest::binary>>), do: {v, rest}

  # length-delimited (wire type 2) — strings, bytes, embedded messages
  defp field_value(2, binary) do
    {len, rest} = varint(binary)
    <<data::binary-size(len), rest2::binary>> = rest
    {data, rest2}
  end

  # 32-bit fixed (wire type 5)
  defp field_value(5, <<v::little-32, rest::binary>>), do: {v, rest}

  # skip unknown wire types gracefully
  defp field_value(_, rest), do: {nil, rest}

  @doc "Return the first value for a field number, or nil."
  def get(fields, num) do
    case List.keyfind(fields, num, 0) do
      {^num, v} -> v
      nil       -> nil
    end
  end

  @doc "Return all values for a field number."
  def get_all(fields, num) do
    fields |> Enum.filter(&match?({^num, _}, &1)) |> Enum.map(&elem(&1, 1))
  end

  # Decode a varint from the front of a binary.
  defp varint(binary), do: varint(binary, 0, 0)

  defp varint(<<0::1, b::7, rest::binary>>, acc, shift),
    do: {acc ||| b <<< shift, rest}

  defp varint(<<1::1, b::7, rest::binary>>, acc, shift),
    do: varint(rest, acc ||| b <<< shift, shift + 7)
end
