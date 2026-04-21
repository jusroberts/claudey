defmodule WigglebotServer.BuildManager do
  use Agent

  require Logger

  @wigglebot_path "/home/justin/Development/claudey/WiggleBot"
  @builds_dir Path.join([:code.priv_dir(:wigglebot_server), "static", "builds"])

  def start_link(_opts) do
    Agent.start_link(fn -> %{status: :idle, message: nil} end, name: __MODULE__)
  end

  def status do
    Agent.get(__MODULE__, & &1)
    |> Map.put(:builds, list_builds())
  end

  def trigger do
    Agent.get_and_update(__MODULE__, fn state ->
      if state.status == :building do
        {{:error, :already_building}, state}
      else
        {{:ok}, %{state | status: :building, message: "Build started"}}
      end
    end)
    |> case do
      {:ok} ->
        Task.start(fn -> run_build() end)
        :ok

      {:error, :already_building} ->
        {:error, "Build already in progress"}
    end
  end

  def list_builds do
    File.mkdir_p!(@builds_dir)

    Path.wildcard(Path.join(@builds_dir, "*.apk"))
    |> Enum.map(fn path ->
      stat = File.stat!(path, time: :posix)
      %{
        filename: Path.basename(path),
        size: stat.size,
        built_at: stat.mtime,
        url: "/builds/#{Path.basename(path)}"
      }
    end)
    |> Enum.sort_by(& &1.built_at, :desc)
  end

  defp run_build do
    Logger.info("BuildManager: starting assembleDebug in #{@wigglebot_path}")

    rescue_result = fn ->
      run_build_inner()
    end

    try do
      rescue_result.()
    rescue
      e ->
        Logger.error("BuildManager: exception in run_build: #{inspect(e)}")
        Agent.update(__MODULE__, &%{&1 | status: :error, message: inspect(e)})
    end
  end

  defp run_build_inner do

    gradlew = Path.join(@wigglebot_path, "gradlew")

    case System.cmd(gradlew, ["assembleDebug"],
           cd: @wigglebot_path,
           stderr_to_stdout: true
         ) do
      {_output, 0} ->
        apk_src = Path.join(@wigglebot_path, "app/build/outputs/apk/debug/app-debug.apk")
        timestamp = System.os_time(:second)
        dest = Path.join(@builds_dir, "app-debug-#{timestamp}.apk")
        File.mkdir_p!(@builds_dir)

        case File.cp(apk_src, dest) do
          :ok ->
            Logger.info("BuildManager: done → #{Path.basename(dest)}")
            Agent.update(__MODULE__, &%{&1 | status: :done, message: Path.basename(dest)})

          {:error, reason} ->
            Logger.error("BuildManager: copy failed: #{inspect(reason)}")
            Agent.update(__MODULE__, &%{&1 | status: :error, message: "Copy failed: #{inspect(reason)}"})
        end

      {output, code} ->
        Logger.error("BuildManager: gradle exited #{code}")
        tail = output |> String.split("\n") |> Enum.take(-10) |> Enum.join("\n")
        Agent.update(__MODULE__, &%{&1 | status: :error, message: "Exit #{code}: #{tail}"})
    end
  end
end
