defmodule WigglebotServerWeb.Router do
  use WigglebotServerWeb, :router

  pipeline :api do
    plug :accepts, ["json"]
  end

  scope "/", WigglebotServerWeb do
    pipe_through :api

    get "/health", HealthController, :index
  end

  scope "/api", WigglebotServerWeb do
    pipe_through :api

    get "/brief/run", BriefController, :run
    get "/brief/commute", BriefController, :commute
    post "/park/book", ParkController, :book
    get "/tmux/sessions", TmuxController, :sessions
    post "/build/trigger", BuildController, :trigger
    get "/build/status", BuildController, :status
    get "/build/list", BuildController, :list
  end

  # Enable LiveDashboard and Swoosh mailbox preview in development
  if Application.compile_env(:wigglebot_server, :dev_routes) do
    # If you want to use the LiveDashboard in production, you should put
    # it behind authentication and allow only admins to access it.
    # If your application does not have an admins-only section yet,
    # you can use Plug.BasicAuth to set up some basic authentication
    # as long as you are also using SSL (which you should anyway).
    import Phoenix.LiveDashboard.Router

    scope "/dev" do
      pipe_through [:fetch_session, :protect_from_forgery]

      live_dashboard "/dashboard", metrics: WigglebotServerWeb.Telemetry
      forward "/mailbox", Plug.Swoosh.MailboxPreview
    end
  end
end
