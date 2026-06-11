defmodule WigglebotServer.HomeFs do
  @moduledoc """
  Filesystem access constrained to the server user's home directory.
  Used by the tmux new-session flow and the client's directory picker.
  Paths are symlink-resolved before the containment check.
  """

  def home, do: System.user_home!()

  @doc """
  Resolves `path` (absolute, or relative to $HOME; nil/"" means $HOME) and
  verifies it is an existing directory inside $HOME.
  """
  def resolve_dir(path) when path in [nil, ""], do: {:ok, home()}

  def resolve_dir(path) do
    expanded = Path.expand(path, home())

    case System.cmd("realpath", ["-e", expanded], stderr_to_stdout: true) do
      {out, 0} ->
        real = String.trim(out)

        cond do
          not inside_home?(real) -> {:error, "path is outside the home directory"}
          not File.dir?(real) -> {:error, "not a directory"}
          true -> {:ok, real}
        end

      _ ->
        {:error, "directory does not exist"}
    end
  end

  @doc """
  Lists subdirectories of `path` (constrained like `resolve_dir/1`).
  Returns {:ok, %{path: abs, parent: abs | nil, dirs: [name]}}.
  Hidden directories are excluded.
  """
  def list_dirs(path) do
    with {:ok, dir} <- resolve_dir(path) do
      dirs =
        case File.ls(dir) do
          {:ok, entries} ->
            entries
            |> Enum.reject(&String.starts_with?(&1, "."))
            |> Enum.filter(&File.dir?(Path.join(dir, &1)))
            |> Enum.sort()

          _ ->
            []
        end

      parent =
        if dir == home() do
          nil
        else
          Path.dirname(dir)
        end

      {:ok, %{path: dir, parent: parent, home: home(), dirs: dirs}}
    end
  end

  defp inside_home?(real_path) do
    home = home()
    real_path == home or String.starts_with?(real_path, home <> "/")
  end
end
