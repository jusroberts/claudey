defmodule WigglebotServer.Tools.Registry do
  @moduledoc """
  Declares all tools and whether each runs server-side or must be
  delegated to the Android device.
  """

  @tools [
    # ── Device-side (Android executes these) ─────────────────────────────────
    %{
      side: :device,
      type: "function",
      function: %{
        name: "media_play_pause",
        description: "Toggle play/pause for whatever is currently active in a media app.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "media_next_track",
        description: "Skip to the next track in the active media app.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "media_previous_track",
        description: "Go back to the previous track in the active media app.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "spotify_search_play",
        description: "Open Spotify and search for a song, album, artist, or playlist.",
        parameters: %{
          type: "object",
          properties: %{
            query: %{type: "string", description: "Search query"},
            type: %{
              type: "string",
              description: "Result type",
              enum: ["track", "album", "artist", "playlist"]
            }
          },
          required: ["query"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "audible_open",
        description: "Open the Audible app.",
        parameters: %{
          type: "object",
          properties: %{
            title: %{type: "string", description: "Optional audiobook title to search for"}
          }
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "launch_app",
        description: "Open any installed app by its common name.",
        parameters: %{
          type: "object",
          properties: %{
            app_name: %{type: "string", description: "Common name of the app"}
          },
          required: ["app_name"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "open_url",
        description: "Open a URL in the default browser.",
        parameters: %{
          type: "object",
          properties: %{
            url: %{type: "string", description: "Full URL including scheme"}
          },
          required: ["url"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "set_volume",
        description: "Set the media volume on the device.",
        parameters: %{
          type: "object",
          properties: %{
            level: %{
              type: "string",
              description: "Volume level: 'mute', 'low', 'medium', 'high', or '50%'"
            }
          },
          required: ["level"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "send_notification",
        description: "Post a local notification to the user.",
        parameters: %{
          type: "object",
          properties: %{
            title: %{type: "string", description: "Notification title"},
            body: %{type: "string", description: "Notification body text"}
          },
          required: ["title", "body"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "get_installed_apps",
        description: "Returns a list of installed apps on the device.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "get_location",
        description: "Returns the device's current GPS coordinates.",
        parameters: %{type: "object", properties: %{}}
      }
    },

    # ── Server-side ───────────────────────────────────────────────────────────
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_weather",
        description:
          "Get current weather conditions. Defaults to device GPS location. Pass a city name to check elsewhere.",
        parameters: %{
          type: "object",
          properties: %{
            location: %{
              type: "string",
              description: "Optional city or place name. Omit to use device GPS."
            }
          }
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "find_nearby",
        description: "Find nearby places using OpenStreetMap.",
        parameters: %{
          type: "object",
          properties: %{
            type: %{
              type: "string",
              enum: ["restaurant", "gas_station", "coffee", "parking", "pharmacy", "supermarket"]
            },
            location: %{type: "string", description: "Optional place name. Omit for device GPS."},
            radius_meters: %{type: "string", description: "Search radius. Default 5000."}
          },
          required: ["type"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_transit",
        description: "Get transit directions using Google Maps.",
        parameters: %{
          type: "object",
          properties: %{
            destination: %{type: "string", description: "Where to go"},
            origin: %{
              type: "string",
              description: "Starting point. Omit entirely to use device GPS."
            }
          },
          required: ["destination"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "navigate_to",
        description: "Start turn-by-turn navigation via Waze or Google Maps.",
        parameters: %{
          type: "object",
          properties: %{
            destination: %{type: "string", description: "Destination address or place name"}
          },
          required: ["destination"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_net_worth",
        description:
          "Get total net worth from Actual Budget: every account balance plus the overall total.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_account_balance",
        description:
          "Get the balance of one Actual Budget account by (fuzzy) name, e.g. 'credit card', 'checking'.",
        parameters: %{
          type: "object",
          properties: %{
            account: %{type: "string", description: "Account name or part of it"}
          },
          required: ["account"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_spending_summary",
        description: "Spending by category over the last N days (default 30) from Actual Budget.",
        parameters: %{
          type: "object",
          properties: %{
            days: %{type: "string", description: "Number of days to look back. Default 30."}
          }
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_last_finance_report",
        description:
          "Get the most recent weekly spending-anomaly report that was sent to the phone.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "remember",
        description:
          "Save a long-lived note to memory, e.g. preferences, facts about the user, things to keep in mind.",
        parameters: %{
          type: "object",
          properties: %{
            note: %{type: "string", description: "The thing to remember"}
          },
          required: ["note"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "recall",
        description:
          "Search saved memory notes. Omit the query to get the most recent notes.",
        parameters: %{
          type: "object",
          properties: %{
            query: %{type: "string", description: "Optional substring to search for"}
          }
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_week_plan",
        description:
          "Get the running coach's training plan for the current and next week, with today marked.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "replan_week",
        description:
          "Regenerate the running coach's training plan for the current week (e.g. after a missed workout or new race).",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "add_race_event",
        description:
          "Save an upcoming race or running event so the coach can plan toward it.",
        parameters: %{
          type: "object",
          properties: %{
            name: %{type: "string", description: "Event name, e.g. 'Toronto Half Marathon'"},
            date: %{type: "string", description: "Event date, YYYY-MM-DD"},
            distance_km: %{type: "string", description: "Optional distance in km"},
            goal: %{type: "string", description: "Optional goal, e.g. 'sub 1:50'"}
          },
          required: ["name", "date"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "list_race_events",
        description: "List upcoming races/running events the coach knows about.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_go_train_schedule",
        description:
          "Get the next GO Train departures on the Milton line. Use when the user asks 'when is the next train to Toronto', 'next train to Milton', or similar.",
        parameters: %{
          type: "object",
          properties: %{
            direction: %{
              type: "string",
              enum: ["toronto", "milton"],
              description:
                "'toronto' = Milton GO → Union Station; 'milton' = Union Station → Milton GO"
            }
          },
          required: ["direction"]
        }
      }
    }
  ]

  def all, do: @tools

  def for_llama do
    Enum.map(@tools, &Map.drop(&1, [:side]))
  end

  def side(tool_name) do
    case Enum.find(@tools, &(&1.function.name == tool_name)) do
      %{side: side} -> side
      nil -> :unknown
    end
  end
end
