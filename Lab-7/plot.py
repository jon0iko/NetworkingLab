import matplotlib.pyplot as plt

def plot_tcp_congestion_control(reno_file, tahoe_file, max_rounds=30):
    # Read data from the files
    with open(reno_file, 'r') as f:
        reno_data = f.readlines()

    with open(tahoe_file, 'r') as f:
        tahoe_data = f.readlines()

    # Parse the data into round number and cwnd values
    reno_rounds, reno_cwnd = zip(*[map(int, line.split(' : ')) for line in reno_data])
    tahoe_rounds, tahoe_cwnd = zip(*[map(int, line.split(' : ')) for line in tahoe_data])

    # Limit the rounds to a smaller number for better visibility
    reno_rounds_limited = reno_rounds[:max_rounds]
    reno_cwnd_limited = reno_cwnd[:max_rounds]
    tahoe_rounds_limited = tahoe_rounds[:max_rounds]
    tahoe_cwnd_limited = tahoe_cwnd[:max_rounds]

    # Plot the results with limited rounds
    plt.figure(figsize=(10,6))
    plt.plot(reno_rounds_limited, reno_cwnd_limited, label='TCP Reno', color='b', marker='o')
    plt.plot(tahoe_rounds_limited, tahoe_cwnd_limited, label='TCP Tahoe', color='r', marker='x')

    plt.title("Transmission Round vs Congestion Window (cwnd) - Limited Rounds")
    plt.xlabel("Transmission Round (x-axis)")
    plt.ylabel("Congestion Window (cwnd) [Packets]")
    plt.legend()
    plt.grid(True)
    plt.tight_layout()

    # Show the plot
    plt.show()

# Example usage:
# Replace 'reno.txt' and 'tahoe.txt' with the paths to your files
plot_tcp_congestion_control('reno.txt', 'tahoe.txt', max_rounds=30)